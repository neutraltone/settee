/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv.player

import android.content.Context
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.Tracks
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.HttpDataSource
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.upstream.DefaultLoadErrorHandlingPolicy
import androidx.media3.exoplayer.upstream.LoadErrorHandlingPolicy
import app.opentv.core.ViewerError
import app.opentv.data.model.Source
import app.opentv.data.repo.MediaKey
import app.opentv.data.repo.Playable
import app.opentv.data.repo.SubtitlePreference
import app.opentv.isRunningOnTelevision
import java.util.Locale
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient

/**
 * Owns the single [ExoPlayer] instance and everything about switching what it is playing.
 *
 * ## Why one player, reused
 *
 * The obvious implementation of "change channel" is release the player and build a new one.
 * On a low-end TV box that takes long enough that a user pressing channel-up four times in a
 * row can queue four constructions and four teardowns, and the codec ends up in a state where
 * nothing plays until the app is killed. That is the "changing channels too quickly causes
 * streams to fail" class of bug, and it is entirely self-inflicted.
 *
 * OpenTV keeps exactly one player for the lifetime of the screen and only ever swaps its
 * media item. Requests are debounced, and an in-flight switch is cancelled the moment a newer
 * one arrives, so holding channel-up costs one actual tune — the one the user stopped on.
 */
@OptIn(UnstableApi::class)
class PlayerController(
    context: Context,
    private val scope: CoroutineScope,
    httpClient: OkHttpClient,
    /**
     * Turns a key into its stream: [app.opentv.data.repo.PlaybackSources.forWatching]. Asked after
     * the zap delay rather than by the caller, so zapping through Stalker channels mints one link,
     * not one per press, and again on each restart, so a link that has expired is minted anew.
     */
    private val resolve: suspend (MediaKey) -> Playable,
    private val options: Options = Options(),
) {

    /** Channel-surf debounce for this controller — longer for the preview so browsing is calm. */
    private val switchDebounceMillis = if (options.preview) PREVIEW_SWITCH_DEBOUNCE_MILLIS else SWITCH_DEBOUNCE_MILLIS

    sealed interface State {
        data object Idle : State
        data class Buffering(val title: String) : State
        data class Playing(val title: String) : State

        /** Playback stopped and retries are exhausted. [reason] is the sentence and detail line to show. */
        data class Error(val title: String, val reason: ViewerError, val canRetry: Boolean) : State
    }

    /** How this controller's player is set up, for what it will be asked to play. */
    data class Options(
        val subtitlesEnabled: Boolean = true,
        /**
         * Tunes the player for the guide's muted preview pane: a shallow start buffer so a highlighted
         * channel shows a frame quickly, and a longer switch debounce so scrolling the channel list
         * does not tune to every channel passed over. The full-screen players leave this false and keep
         * the deep buffer that rides out a twitchy IPTV source.
         */
        val preview: Boolean = false,
        /**
         * Turns the live player into a shallow DVR: a retained back-buffer so the viewer can pause and
         * rewind live TV within the last few minutes. Off by default because holding minutes of video
         * in memory is a real cost on a cheap box; the recording player and preview never set it.
         */
        val dvr: Boolean = false,
        /**
         * When set, `smb://` media (a recording on a NAS) is read through this source so it plays and
         * seeks in-app. Null for the live/VOD players, which never see an smb URI.
         */
        val smbDataSourceFactory: androidx.media3.datasource.DataSource.Factory? = null,
        /**
         * When set, `optvrec://<id>` media (a recording still being written) is read through this
         * tail-following source so it can be watched while it records. Null everywhere except the
         * recording player.
         */
        val growingDataSourceFactory: androidx.media3.datasource.DataSource.Factory? = null,
        /**
         * Playing a recording that is still being written (watch-while-recording). Playback is held a
         * cushion *behind* the file's growing edge (see the LIVE_REC_* constants) so the bursty arrival
         * of an IPTV source — worst when the recording is read back over SMB from a NAS, where each read
         * is a network round-trip — rides over the gaps instead of stalling on every one.
         */
        val liveRecording: Boolean = false,
        /**
         * Builds the player from the configured builder. Tests swap in a player with fake media sources
         * and a fake clock; everything else keeps the default.
         */
        val buildPlayer: (ExoPlayer.Builder) -> ExoPlayer = ExoPlayer.Builder::build,
        /** What this device can decode, which decides the tracks it refuses. */
        val decoders: DecoderList = DeviceDecoders(),
    )

    /**
     * A Quality that playback may step down to: what plays it, what a viewer calls it, and where to
     * start, since a Movie's or Episode's Qualities each keep their own resume point.
     */
    data class Quality(val key: MediaKey, val label: String, val startPositionMillis: Long = 0L)

    /**
     * [startPositionMillis] is ignored for live streams, which are never resumed. [lower] are the
     * Qualities to step down to, best first, should this device turn out not to play this one, which
     * [label] names; none for a Quality the viewer picked themselves. [stepsForSound] also steps down
     * when none of the sound can play: a film is worth nothing silent, where a Channel's picture may be.
     */
    data class Request(
        val key: MediaKey,
        val startPositionMillis: Long = 0L,
        val label: String = "",
        val lower: List<Quality> = emptyList(),
        val stepsForSound: Boolean = false,
    )

    private val _state = MutableStateFlow<State>(State.Idle)
    val state: StateFlow<State> = _state.asStateFlow()

    /** The current stream's tracks (audio/text/video), for the in-player pickers. */
    private val _tracks = MutableStateFlow(Tracks.EMPTY)
    val tracks: StateFlow<Tracks> = _tracks.asStateFlow()

    /**
     * Whether the player means to play. The screens draw "paused" from this rather than keeping their
     * own flag, because the controller resumes playback by itself (a new stream, an automatic restart
     * after a dropped connection) and a copy would then claim the picture is paused while it plays.
     */
    private val _playWhenReady = MutableStateFlow(false)
    val playWhenReady: StateFlow<Boolean> = _playWhenReady.asStateFlow()

    private val strings = context.applicationContext

    /** Decided once, as the app does at launch, so sentences say "this TV" or "this device". */
    private val onTelevision = isRunningOnTelevision(context)

    private val guard: StreamGuard = StreamGuard(
        strings,
        scope,
        options.decoders,
        moveDown = { forSound ->
            val playing = current?.takeIf { !forSound || it.stepsForSound }
            playing?.lower?.firstOrNull()?.let { next ->
                gaveUp = true
                player.stop()
                play(
                    Request(
                        next.key,
                        next.startPositionMillis,
                        next.label,
                        playing.lower.drop(1),
                        playing.stepsForSound,
                    ),
                    debounce = false,
                )
                StreamGuard.Step(playing.label, next)
            }
        },
        onTelevision = onTelevision,
    ) { reason, canRetry ->
        // Before anything reports an error, so none flashes up. The player's stopped and marked so
        // first, so a state it's already reporting can't cover the sentence.
        gaveUp = true
        player.stop()
        _state.value = State.Error(_title.value, reason, canRetry)
    }

    /** A short message for the viewer, up for about 5 seconds. It takes no focus. */
    val notice: StateFlow<String?> get() = guard.notice

    /** The Quality this stream stepped down to by itself, so a screen can show it as the one playing. */
    val steppedDownTo: StateFlow<MediaKey?> get() = guard.steppedDownTo

    private var switchJob: Job? = null
    private val streamSubtitles = StreamSubtitles()
    private var current: Request? = null

    /** What [titled] is called, once [resolve] has said. */
    private val _title = MutableStateFlow("")
    val title: StateFlow<String> = _title.asStateFlow()
    private var titled: MediaKey? = null
    private var loaded: MediaKey? = null
    private var consecutiveFailures = 0

    /** Whether the controller itself has stopped this stream, until the next one starts. */
    private var gaveUp = false

    /**
     * Held so the User-Agent can be swapped per source before each tune.
     *
     * Providers commonly 403 any client whose UA they do not recognise, and the workaround —
     * "try a different User-Agent" — is useless unless the setting actually reaches the
     * player's HTTP layer. Setting it on the factory applies it to data sources created for
     * subsequent loads, which is exactly the granularity we need.
     */
    private val httpFactory = OkHttpDataSource.Factory(httpClient).apply {
        setDefaultRequestProperties(mapOf("User-Agent" to Source.DEFAULT_USER_AGENT))
    }

    private val dataSourceFactory: androidx.media3.datasource.DataSource.Factory =
        DefaultDataSource.Factory(context, httpFactory).let { default ->
            val custom = buildMap<String, androidx.media3.datasource.DataSource.Factory> {
                options.smbDataSourceFactory?.let { put("smb", it) }
                options.growingDataSourceFactory?.let { put("optvrec", it) }
            }
            if (custom.isEmpty()) default else RoutingDataSourceFactory(default, custom)
        }

    /**
     * Retry policy tuned for IPTV rather than for CDNs.
     *
     * A provider under load returns 403, 429 or 500 for a few seconds and then serves the
     * stream perfectly well. ExoPlayer's default is to give up almost immediately on those,
     * which surfaces to the user as a channel that "doesn't work" but plays fine on the
     * second try. We retry them with backoff instead. 401 and 404 are not retried — those
     * genuinely will not fix themselves.
     */
    private val loadErrorPolicy = object : DefaultLoadErrorHandlingPolicy() {
        override fun getRetryDelayMsFor(info: LoadErrorHandlingPolicy.LoadErrorInfo): Long {
            val statusCode =
                (info.exception as? HttpDataSource.InvalidResponseCodeException)?.responseCode

            return when (statusCode) {
                401, 404, 410 -> C_TIME_UNSET
                403, 405, 429, 500, 502, 503, 504 -> backoffFor(info.errorCount)
                null -> backoffFor(info.errorCount) // network blips
                else -> super.getRetryDelayMsFor(info)
            }
        }

        override fun getMinimumLoadableRetryCount(dataType: Int): Int = MAX_LOAD_RETRIES

        private fun backoffFor(errorCount: Int): Long = if (errorCount > MAX_LOAD_RETRIES) {
            C_TIME_UNSET
        } else {
            minOf(INITIAL_BACKOFF_MILLIS * (1L shl (errorCount - 1)), MAX_BACKOFF_MILLIS)
        }
    }

    /**
     * Held so captions can be turned on and off at runtime. Prefer the device language when
     * captions are on; [setSubtitlesEnabled] flips the text renderer off entirely.
     */
    private val trackSelector = SizeLimitedTrackSelector(context, options.decoders).apply {
        parameters = buildUponParameters()
            .setPreferredTextLanguage(Locale.getDefault().language)
            .setSelectUndeterminedTextLanguage(true)
            .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, !options.subtitlesEnabled)
            .build()
    }

    private val bufferPlan = BufferPlan.forPlayer(options.preview, options.dvr, options.liveRecording)

    /** Says when the buffer is full, which is when a live pause has to end. */
    internal val bufferWatch = BufferWatch(bufferPlan.loadControl())

    /** Whether the player has stopped loading because its buffer is full. */
    val bufferFull: StateFlow<Boolean> get() = bufferWatch.full

    /** The longest live TV can stay paused on this device; 0 unless live pause is on. */
    val livePauseMillis: Int get() = bufferPlan.backBufferMillis

    // Fallback is for the full-screen players: a muted preview that can't start says so on its tile
    // rather than spend a cheap box's time trying every decoder for each channel passed over.
    val player: ExoPlayer = ExoPlayer.Builder(
        context,
        DefaultRenderersFactory(context).setEnableDecoderFallback(!options.preview),
    )
        .setMediaSourceFactory(
            DefaultMediaSourceFactory(dataSourceFactory)
                .setLoadErrorHandlingPolicy(loadErrorPolicy),
        )
        .setTrackSelector(trackSelector)
        .setLoadControl(bufferWatch)
        .let(options.buildPlayer)
        .apply {
            addAnalyticsListener(DecoderLog.listener)
            addAnalyticsListener(guard.firstFrameListener)
            addListener(object : Player.Listener {
                override fun onTracksChanged(tracks: Tracks) {
                    _tracks.value = tracks
                    // What's left of the last stream says nothing about the one on its way.
                    if (!isSwitching) guard.tracksChanged(tracks, soundWanted = audioEnabled)
                }

                override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
                    _playWhenReady.value = playWhenReady
                    // Full before a pause says nothing about after it: the player asks again
                    // within a second, and a pause only ends on an answer given while paused.
                    bufferWatch.update(full = false)
                }

                override fun onPlaybackStateChanged(playbackState: Int) {
                    if (gaveUp) return
                    _state.value = when (playbackState) {
                        Player.STATE_BUFFERING -> State.Buffering(_title.value)
                        Player.STATE_READY -> {
                            consecutiveFailures = 0
                            State.Playing(_title.value)
                        }
                        Player.STATE_IDLE -> _state.value
                        Player.STATE_ENDED -> State.Idle
                        else -> _state.value
                    }
                }

                override fun onPlayerError(error: PlaybackException) {
                    consecutiveFailures++
                    guard.leaving()
                    DecoderLog.logFailure(error)
                    val request = current
                    // Restarting can't change what this device can decode, and on a portal it would
                    // only mint more links for a stream that will fail the same way.
                    val canRetry = !PlaybackErrors.isUnplayableFormat(error) &&
                        consecutiveFailures < MAX_AUTO_RESTARTS
                    // Running out of memory took the Stick over a minute (#180), so a silent
                    // restart would hide the message for another minute. Retry stays on offer:
                    // the memory may be free by the time the viewer asks.
                    val canRestart = canRetry && !PlaybackErrors.isOutOfMemory(error)
                    _state.value = State.Error(
                        title = _title.value,
                        // The last stream's format lingers until this one's tracks are known, so only a
                        // selected video track's counts.
                        reason = PlaybackErrors.describe(
                            strings,
                            error,
                            onTelevision,
                            player.videoFormat.takeIf { player.currentTracks.isTypeSelected(C.TRACK_TYPE_VIDEO) },
                        ),
                        canRetry = canRetry,
                    )
                    // One silent restart covers the common case of a provider dropping the
                    // connection when another device on the account starts streaming.
                    if (canRestart && request != null) {
                        scope.launch {
                            delay(AUTO_RESTART_DELAY_MILLIS)
                            if (current == request) play(request, debounce = false)
                        }
                    }
                }
            })
        }

    /**
     * Whether a sound track is selected, for this stream and the ones after it. Off selects no audio
     * track rather than setting the volume to 0: Dolby passed straight through over HDMI can ignore
     * the player's volume, and it spares a cheap box decoding audio nobody is listening to.
     */
    var audioEnabled: Boolean
        get() = C.TRACK_TYPE_AUDIO !in player.trackSelectionParameters.disabledTrackTypes
        set(enabled) {
            player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
                .setTrackTypeDisabled(C.TRACK_TYPE_AUDIO, !enabled)
                .build()
        }

    init {
        if (options.preview) audioEnabled = false
    }

    /**
     * Switches playback.
     *
     * @param debounce when true (the default for channel surfing) the switch waits briefly so
     * that rapid presses collapse into a single tune. Pass false for a deliberate selection.
     * @param skipped what the Quality this device isn't going to try is called, if this plays in its place.
     */
    fun play(request: Request, debounce: Boolean = true, skipped: String? = null) {
        // Cancelling here is what makes fast channel-changing safe: the previous switch never
        // reaches the player, so we never stack prepares.
        switchJob?.cancel()
        // An automatic restart plays the same request again and keeps counting, so a stream that
        // keeps dropping stops after MAX_AUTO_RESTARTS rather than retrying (and, on a portal,
        // minting a link) forever.
        if (request != current) consecutiveFailures = 0
        if (request.key != current?.key) guard.forgetNotices()
        current = request
        guard.leaving()

        switchJob = scope.launch {
            if (debounce) delay(switchDebounceMillis)

            // A restart keeps its title while it asks again; a new channel has none until it's known.
            if (request.key != titled) _title.value = ""
            _state.value = State.Buffering(_title.value)
            val playable = when (val resolved = resolve(request.key)) {
                is Playable.Ready -> resolved
                is Playable.Failed -> {
                    val title = resolved.title.ifEmpty { _title.value }
                    _state.value = State.Error(title, PlaybackErrors.describe(strings, resolved.failure), true)
                    return@launch
                }
            }
            _title.value = playable.title
            titled = request.key
            httpFactory.setDefaultRequestProperties(mapOf("User-Agent" to playable.userAgent))
            _state.value = State.Buffering(playable.title)

            val mediaItem = MediaItem.Builder()
                .setUri(playable.url)
                .apply {
                    // Only meaningful for live streams; setting it on VOD skews seeking.
                    if (playable.isLive) {
                        setLiveConfiguration(
                            MediaItem.LiveConfiguration.Builder()
                                .setTargetOffsetMs(LIVE_TARGET_OFFSET_MILLIS)
                                .build(),
                        )
                    }
                }
                .build()

            with(player) {
                // stop() rather than release(): keeps the codec and the surface alive.
                stop()
                gaveUp = false
                guard.started()
                clearMediaItems()
                // Each stream starts with text off, then applySubtitlePreference picks its track.
                // Left on from the last stream, the selector would choose a track itself, in the
                // device language, and the Profile's language would only reach the first channel.
                disableText()
                streamSubtitles.newStream()
                setMediaItem(mediaItem)
                loaded = request.key
                if (!playable.isLive && request.startPositionMillis > 0) {
                    seekTo(request.startPositionMillis)
                }
                playWhenReady = true
                prepare()
            }
        }
        skipped?.let { guard.steppedDown(it, Quality(request.key, request.label)) }
    }

    /** Whether a [play] hasn't reached the player yet, so [player] still holds the last stream. */
    val isSwitching: Boolean get() = switchJob?.isActive == true

    fun retry() {
        consecutiveFailures = 0
        current?.let { play(it, debounce = false) }
    }

    /**
     * Turns subtitles on or off for the current stream. On is *not* merely "allow the text renderer" — that leaves it
     * to the selector to guess a language, which for a lot of IPTV streams guesses nothing and
     * the user sees no captions even though they turned them on. So On explicitly selects a track,
     * in [preferredLanguage] when the stream has it (see [chooseSubtitleTrack]); Off disables text
     * entirely.
     */
    fun setSubtitlesEnabled(enabled: Boolean, preferredLanguage: String? = null) {
        if (!enabled) {
            disableText()
            return
        }
        val candidates = _tracks.value.groups
            .filter { it.type == C.TRACK_TYPE_TEXT }
            .flatMap { group -> (0 until group.length).map { group to it } }
        val choice = chooseSubtitleTrack(
            candidates.map { (group, i) -> SubtitleTrack(group.getTrackFormat(i).language, group.isTrackSupported(i)) },
            preferred = preferredLanguage,
            device = Locale.getDefault().language,
        )
        if (choice != null) {
            val (group, i) = candidates[choice]
            selectTrack(group, i)
        } else {
            // Nothing to select yet — allow the renderer so a track that arrives later can be
            // picked up, and re-assert once tracks change.
            player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
                .clearOverridesOfType(C.TRACK_TYPE_TEXT)
                .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false)
                .build()
        }
    }

    /**
     * Applies a Profile's Subtitle Preference to the current stream, once its text tracks are known
     * (see [StreamSubtitles]). Safe to call on every tracks or preference change.
     */
    fun applySubtitlePreference(preference: SubtitlePreference?) {
        val hasText = _tracks.value.groups.any { it.type == C.TRACK_TYPE_TEXT }
        if (streamSubtitles.shouldTurnOn(preference, hasText)) setSubtitlesEnabled(true, preference?.language)
    }

    /** Force a specific audio or text track on (used by the in-player pickers). */
    fun selectTrack(group: Tracks.Group, trackIndex: Int) {
        player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
            .setOverrideForType(TrackSelectionOverride(group.mediaTrackGroup, trackIndex))
            .setTrackTypeDisabled(group.type, false)
            .build()
    }

    /** Turn subtitles off entirely. */
    fun disableText() {
        player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
            .clearOverridesOfType(C.TRACK_TYPE_TEXT)
            .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true)
            .build()
    }

    fun seekBackward() = player.seekBy(-SEEK_INCREMENT_MILLIS)

    fun seekForward() = player.seekBy(SEEK_INCREMENT_MILLIS)

    val isSeekable: Boolean get() = player.isCurrentMediaItemSeekable

    /**
     * How far through what was asked for playback is. While the next stream resolves, the player still
     * reports the last one's position, and a seek bar reading that would show the wrong title's place.
     */
    val positionMillis: Long
        get() = current?.takeIf { it.key != loaded }?.startPositionMillis
            ?: player.currentPosition.coerceAtLeast(0)

    fun stop() {
        switchJob?.cancel()
        guard.leaving()
        guard.forgetNotices()
        current = null
        loaded = null
        player.stop()
        player.clearMediaItems()
        _state.value = State.Idle
    }

    fun release() {
        switchJob?.cancel()
        guard.release()
        player.release()
    }

    companion object {
        /** How far rewind and fast-forward skip, on screen and on the remote. */
        const val SEEK_INCREMENT_MILLIS = 15_000L

        /** ExoPlayer's "do not retry" sentinel. */
        private const val C_TIME_UNSET = androidx.media3.common.C.TIME_UNSET

        private const val SWITCH_DEBOUNCE_MILLIS = 350L
        private const val INITIAL_BACKOFF_MILLIS = 500L
        private const val MAX_BACKOFF_MILLIS = 8_000L

        private const val MAX_LOAD_RETRIES = 5
        private const val MAX_AUTO_RESTARTS = 3
        private const val AUTO_RESTART_DELAY_MILLIS = 1_500L

        // Debounce channel-surfing in the guide preview harder, so scrolling the list doesn't tune
        // to every channel in passing.
        private const val PREVIEW_SWITCH_DEBOUNCE_MILLIS = 700L

        private const val LIVE_TARGET_OFFSET_MILLIS = 10_000L
    }
}

// Seeks by our own step rather than the player's seekBack()/seekForward(), so the step is
// PlayerController's constant whichever way the player was built. Clamped the same way ExoPlayer clamps.
private fun Player.seekBy(deltaMillis: Long) {
    if (!isCurrentMediaItemSeekable) return
    val end = duration.takeIf { it != C.TIME_UNSET } ?: Long.MAX_VALUE
    seekTo((currentPosition + deltaMillis).coerceIn(0L, end))
}
