/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv

import android.content.Context
import android.os.Looper
import android.view.Surface
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.HandlerWrapper
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.drm.DrmSessionManagerProvider
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.upstream.LoadErrorHandlingPolicy
import androidx.media3.exoplayer.video.VideoRendererEventListener
import androidx.media3.test.utils.ExoPlayerTestRunner
import androidx.media3.test.utils.FakeAudioRenderer
import androidx.media3.test.utils.FakeClock
import androidx.media3.test.utils.FakeMediaSource
import androidx.media3.test.utils.FakeTimeline
import androidx.media3.test.utils.FakeVideoRenderer
import androidx.media3.test.utils.TestExoPlayerBuilder
import androidx.media3.test.utils.robolectric.TestPlayerRunHelper
import app.opentv.core.JourneyTimer
import app.opentv.data.repo.MediaKey
import app.opentv.data.repo.Playable
import app.opentv.diagnostics.PlaybackDiagnostics
import app.opentv.player.DecoderList
import app.opentv.player.DeviceDecoders
import app.opentv.player.PlayerController
import app.opentv.player.torrent.TorrentEngine
import java.io.IOException
import java.time.Duration
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import okhttp3.OkHttpClient
import org.robolectric.Shadows.shadowOf

/*
 * A real ExoPlayer for the player tests (#68), playing fake media instead of a stream. Its clock only
 * moves when a test moves it, so a "playing" film stays where the test put it and a position read
 * after a skip is exact.
 */

/** A one-hour film: seekable, with a fixed length. */
@UnstableApi
val FILM = FakeTimeline.TimelineWindowDefinition(
    /* isSeekable= */
    true,
    /* isDynamic= */
    false,
    /* durationUs= */
    60L * 60 * C.MICROS_PER_SECOND,
)

/** A recording still being written: ExoPlayer can't seek it, so the player skips within what's buffered. */
@UnstableApi
val RECORDING = FakeTimeline.TimelineWindowDefinition(
    /* isSeekable= */
    false,
    /* isDynamic= */
    true,
    /* durationUs= */
    10L * 60 * C.MICROS_PER_SECOND,
)

/**
 * A live channel with live pause's 2 minutes behind the edge to seek into, playing near the edge.
 * ExoPlayer counts a window as live only when its media item carries a live configuration.
 */
@UnstableApi
val LIVE = FakeTimeline.TimelineWindowDefinition(
    /* periodCount= */
    1,
    /* id= */
    "live",
    /* isSeekable= */
    true,
    /* isDynamic= */
    true,
    /* isLive= */
    true,
    /* isPlaceholder= */
    false,
    /* durationUs= */
    120L * C.MICROS_PER_SECOND,
    /* defaultPositionUs= */
    110L * C.MICROS_PER_SECOND,
    /* windowOffsetInFirstPeriodUs= */
    0L,
    listOf(androidx.media3.common.AdPlaybackState.NONE),
    MediaItem.Builder()
        .setUri("https://example.com/live")
        .setLiveConfiguration(MediaItem.LiveConfiguration.Builder().build())
        .build(),
)

/** A live channel that can't be sought, like most providers' live TS: the player has no seek bar. */
@UnstableApi
val UNSEEKABLE_LIVE = FakeTimeline.TimelineWindowDefinition(
    1,
    "live",
    /* isSeekable= */
    false,
    true,
    true,
    false,
    120L * C.MICROS_PER_SECOND,
    110L * C.MICROS_PER_SECOND,
    0L,
    listOf(androidx.media3.common.AdPlaybackState.NONE),
    LIVE.mediaItem,
)

/**
 * A live channel whose stream comes to an end, as an HLS playlist does when it gains `EXT-X-ENDLIST`, or a
 * TS connection the provider closes cleanly: a short window that isn't dynamic, so ExoPlayer ends it.
 */
@UnstableApi
val ENDING_LIVE = FakeTimeline.TimelineWindowDefinition(
    /* isSeekable= */
    false,
    /* isDynamic= */
    false,
    /* durationUs= */
    100L * C.MICROS_PER_SECOND / 1000,
)

/** A live channel whose provider closes the connection after [SESSION_MILLIS], as a panel limiting sessions does. */
@UnstableApi
val SESSION_LIMITED_LIVE = FakeTimeline.TimelineWindowDefinition(
    /* isSeekable= */
    false,
    /* isDynamic= */
    false,
    /* durationUs= */
    SESSION_MILLIS * 1000,
)

const val SESSION_MILLIS = 20L * 60 * 1000

/** What a stream carries, for the tests that care about its sound. */
@UnstableApi
val PICTURE_AND_SOUND = listOf(ExoPlayerTestRunner.VIDEO_FORMAT, ExoPlayerTestRunner.AUDIO_FORMAT)

/** A radio station: sound and nothing else. */
@UnstableApi
val SOUND_ONLY = listOf(ExoPlayerTestRunner.AUDIO_FORMAT)

@UnstableApi
class TestPlayers(private val context: Context) {

    // Not auto-advancing: time moves only when a test moves it.
    val clock = FakeClock(false)
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    /** What the streams of controllers made from now on carry. */
    var formats = listOf(ExoPlayerTestRunner.VIDEO_FORMAT)

    /** What the streams at particular addresses carry instead of [formats]. */
    val formatsAt = mutableMapOf<String, List<Format>>()

    /** What the controllers made from now on believe this device can decode. */
    var decoders: DecoderList = DeviceDecoders()

    /** Whether the fake decoder shows anything. Without, it starts and never shows a frame. */
    var showPictures = true

    /**
     * Whether the streams of [controller]s made from now on never start and never fail, after any
     * failing ones, as from a panel that takes the connection and sends nothing.
     */
    var hanging = false

    /** Whether [controller]s made from now on are the recording player's, for one still being written. */
    var liveRecording = false

    /**
     * Whether [controller]s made from now on give up on a stream that never starts. Off, because [untilReady]
     * jumps the main looper's clock to the limit before a fake stream is ready (see
     * [PlayerController.Options.limitStartUp]); [StartUpLimitTest] turns it on.
     */
    var limitStartUp = false

    /** What [controller]s made from now on tell about their opens and errors. */
    var diagnostics: PlaybackDiagnostics? = null

    /** What [controller]s made from now on time their starts of playback with. */
    var journeys: JourneyTimer? = null

    /** How a controller's failing streams fail: by default, as a dropped connection does. */
    var failure: () -> IOException = { IOException("Connection dropped") }

    /** Every stream the controllers were handed, in order. */
    val played = mutableListOf<String>()

    /**
     * A [PlayerController] whose every stream plays [window], resolved by [resolve] (by default a
     * stream at a placeholder address). The first [failing] streams fail with [failure].
     * [preview] makes it the controller a preview pane uses; [torrentEngine] is what it plays torrents with.
     */
    @Suppress("LongParameterList") // Each is a way a test sets up the player; all default to the usual.
    fun controller(
        window: FakeTimeline.TimelineWindowDefinition = FILM,
        dvr: Boolean = false,
        failing: Int = 0,
        preview: Boolean = false,
        torrentEngine: TorrentEngine? = null,
        resolve: suspend (MediaKey) -> Playable = ::placeholder,
    ) = PlayerController(
        context,
        scope,
        OkHttpClient(),
        resolve = resolve,
        PlayerController.Options(
            preview = preview,
            dvr = dvr,
            liveRecording = liveRecording,
            limitStartUp = limitStartUp,
            diagnostics = diagnostics,
            journeys = journeys,
            buildPlayer = {
                val starts = window.takeUnless { hanging }
                TestExoPlayerBuilder(context)
                    .setClock(clock)
                    .setRenderersFactory { handler, video, audio, _, _ ->
                        val events = clock.createHandler(handler.looper, null)
                        arrayOf(PicturingRenderer(events, video) { showPictures }, FakeAudioRenderer(events, audio))
                    }
                    .setMediaSourceFactory(FakeSources(starts, played, failing, formats, formatsAt, failure))
                    .build()
            },
            decoders = decoders,
            torrentEngine = torrentEngine,
        ),
    )

    /**
     * A [PlayerController] on the renderers it has in the app, so real decoders are chosen from the
     * device's codec list. Plays fake H.264 video to [surface]; a decoder only starts when it has one.
     */
    fun realDecoderController(
        preview: Boolean = false,
        surface: Surface,
        resolve: suspend (MediaKey) -> Playable = ::placeholder,
    ) = PlayerController(
        context,
        scope,
        OkHttpClient(),
        resolve = resolve,
        PlayerController.Options(
            preview = preview,
            limitStartUp = limitStartUp,
            buildPlayer = { builder ->
                builder
                    .setClock(FakeClock(true))
                    .setMediaSourceFactory(FakeSources(FILM, played, failing = 0, formats, formatsAt))
                    .build()
                    .apply { setVideoSurface(surface) }
            },
        ),
    )

    /**
     * A [PlayerController] reading [resolve]'s addresses through the app's own data sources rather than fake
     * media, for how a real file fails, over [client] for a web address. Its player runs on [clock], so it only
     * moves on in [until].
     */
    fun realSourceController(client: OkHttpClient = OkHttpClient(), resolve: suspend (MediaKey) -> Playable) =
        PlayerController(
            context,
            scope,
            client,
            resolve = resolve,
            PlayerController.Options(
                limitStartUp = limitStartUp,
                buildPlayer = { builder -> builder.setClock(clock).build() },
            ),
        )

    /** Cancels what the controllers left running, such as an automatic restart, so it can't outlive the test. */
    fun close() = scope.cancel()

    /** Starts [controller] on a stream and lets it buffer until it's playing. */
    fun play(controller: PlayerController, isLive: Boolean = false) {
        controller.play(PlayerController.Request(if (isLive) MediaKey.Live(1) else MediaKey.Movie(1)), debounce = false)
        untilReady(controller.player)
    }

    /**
     * Runs the main thread, where the controller's zap delay and restarts wait, until [done]. Moves its
     * clock on in small steps, letting a portal call on another thread come back in between.
     */
    fun until(done: () -> Boolean) {
        repeat(MAX_TICKS) {
            if (done()) return
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(TICK_MILLIS * 10))
            clock.advanceTime(TICK_MILLIS)
            Thread.sleep(1)
        }
        error("Gave up waiting on the controller")
    }

    fun untilReady(player: ExoPlayer) {
        repeat(MAX_TICKS) {
            if (player.playbackState == Player.STATE_READY) return
            clock.advanceTime(TICK_MILLIS)
            TestPlayerRunHelper.run(player).untilPendingCommandsAreFullyHandled()
        }
        error("The player never got ready: state ${player.playbackState}")
    }

    /** Moves the main thread's clock on by [millis], running whatever was waiting on it. */
    fun advance(millis: Long) = shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(millis))

    /**
     * Runs [controller], whose streams keep failing, until it stops restarting them by itself:
     * [AUTO_TRIES] more streams than [before] were handed over and the last one failed. Then waits
     * out [RESTARTS_MILLIS], so a restart that shouldn't happen would have.
     */
    fun untilItStopsRestarting(controller: PlayerController, before: Int = 0) {
        until { played.size == before + AUTO_TRIES && controller.state.value is PlayerController.State.Error }
        advance(RESTARTS_MILLIS)
    }

    /**
     * The fake decoder reports its first frame only on a surface, and a [androidx.media3.ui.PlayerView]
     * has none under Robolectric. So this one reports it by itself, as soon as it has a format.
     */
    private class PicturingRenderer(
        private val events: HandlerWrapper,
        private val listener: VideoRendererEventListener,
        private val showsPictures: () -> Boolean,
    ) : FakeVideoRenderer(events, listener) {
        override fun onFormatChanged(format: Format) {
            super.onFormatChanged(format)
            if (showsPictures()) events.post { listener.onRenderedFirstFrame(Any(), 0L) }
        }
    }

    /** With no [window], the player waits for one for ever, buffering, and never reports an error. */
    private class FakeSources(
        private val window: FakeTimeline.TimelineWindowDefinition?,
        private val played: MutableList<String>,
        private var failing: Int,
        private val formats: List<Format>,
        private val formatsAt: Map<String, List<Format>>,
        private val failure: () -> IOException = { IOException("Connection dropped") },
    ) : MediaSource.Factory {
        override fun createMediaSource(mediaItem: MediaItem): MediaSource {
            val address = mediaItem.localConfiguration?.uri.toString()
            played += address
            if (failing-- > 0) return FailingSource(failure)
            return FakeMediaSource(
                window?.let(::FakeTimeline),
                *formatsAt.getOrDefault(address, formats).toTypedArray(),
            )
        }

        override fun setDrmSessionManagerProvider(provider: DrmSessionManagerProvider) = this

        override fun setLoadErrorHandlingPolicy(policy: LoadErrorHandlingPolicy) = this

        override fun getSupportedTypes(): IntArray = intArrayOf(C.CONTENT_TYPE_OTHER)
    }

    /** A stream that fails with [failure] before it ever starts. */
    private class FailingSource(private val failure: () -> IOException) : FakeMediaSource(null) {
        override fun maybeThrowSourceInfoRefreshError() = throw failure()
    }

    companion object {
        /** How many times a stream is tried before the player stops restarting it by itself: once, and twice again. */
        const val AUTO_TRIES = 3

        /** Longer than the player waits before restarting by itself, twice over. */
        const val RESTARTS_MILLIS = 10_000L

        private fun placeholder(key: MediaKey) = Playable.Ready(
            key,
            "https://example.com/stream",
            userAgent = "",
            title = "Film",
            isLive = key is MediaKey.Live,
        )

        private const val TICK_MILLIS = 10L
        private const val MAX_TICKS = 500
    }
}
