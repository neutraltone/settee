/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv.ui.vod

import android.view.ViewGroup
import android.view.WindowManager
import androidx.activity.compose.BackHandler
import androidx.annotation.OptIn
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Audiotrack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ClosedCaption
import androidx.compose.material.icons.filled.FastForward
import androidx.compose.material.icons.filled.FastRewind
import androidx.compose.material.icons.filled.FiberManualRecord
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.media3.common.Tracks
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.PlayerView
import app.opentv.R
import app.opentv.core.ServiceLocator
import app.opentv.core.SleepTimer
import app.opentv.core.findActivity
import app.opentv.player.NextEpisodeCountdown
import app.opentv.player.PlayerController
import app.opentv.ui.formatPlaybackTime
import app.opentv.ui.player.PlayerBackButton
import app.opentv.ui.player.PlayerChip
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Plays a movie or episode: a single non-live stream, with resume and a proper transport bar.
 *
 * The bar is drawn in Compose rather than left to the Media3 view's own controller, because that
 * controller needs the embedded view to hold d-pad focus to be summoned — which it doesn't, on a
 * TV inside Compose, so it reads as "no controls". This is the same self-drawn, auto-hiding bar as
 * the live player, with a seek bar added since a film needs scrubbing.
 */
@OptIn(UnstableApi::class)
@Composable
fun VodPlayerScreen(
    mediaKey: String,
    streamUrl: String,
    title: String,
    userAgent: String,
    onBack: () -> Unit,
    viewModel: VodPlayerViewModel = viewModel(),
) {
    val context = LocalContext.current
    val view = LocalView.current
    val graph = remember { ServiceLocator.get(context) }
    val scope = remember { CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate) }
    // A recording that's still being written plays through the tail-following source. It behaves a
    // little differently in the transport: the "length" is how much has been recorded so far, which
    // keeps growing, and seeking is bounded to that recorded extent.
    val growingRec = remember(streamUrl) { streamUrl.startsWith("optvrec://") }
    // The SMB source lets a recording stored on a NAS play and seek in-app; harmless for the
    // http/file URLs of ordinary VOD.
    val controller = remember {
        PlayerController(
            context,
            scope,
            graph.streamingHttpClient,
            subtitlesEnabled = false,
            smbDataSourceFactory = app.opentv.player.SmbDataSource.Factory(graph.settings),
            // Lets an `optvrec://<id>` recording play while it's still being written.
            growingDataSourceFactory =
            app.opentv.player.GrowingRecordingDataSource.Factory(context.applicationContext),
            liveRecording = growingRec,
        )
    }

    val playback = remember {
        object : VodPlayerViewModel.Playback {
            override fun progress() = controller.player.let { p ->
                NextEpisodeCountdown.Playback(
                    positionMillis = p.currentPosition.coerceAtLeast(0),
                    durationMillis = p.duration.takeIf { it > 0 } ?: 0,
                    ended = p.playbackState == Player.STATE_ENDED,
                )
            }

            // Swapping the media item in the same player, never a new one: see PlayerController.
            override fun play(item: VodPlayerViewModel.Item, startPositionMillis: Long) = controller.play(
                PlayerController.Request(
                    url = item.streamUrl,
                    title = item.title,
                    userAgent = item.userAgent,
                    startPositionMillis = startPositionMillis,
                    isLive = false,
                ),
                debounce = false,
            )

            override fun pause() {
                controller.player.playWhenReady = false
            }

            override fun stop() = controller.stop()
        }
    }

    // Keep the screen awake during playback — see the note in PlayerScreen; a film is exactly when
    // the screensaver must not fire. Window flag is the reliable path; keepScreenOn is a backstop.
    DisposableEffect(Unit) {
        val window = context.findActivity()?.window
        window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        view.keepScreenOn = true
        onDispose {
            window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            view.keepScreenOn = false
            // Main.immediate runs this up to the database write before release() and cancel(); the
            // write itself must survive the cancel, or leaving during the credits loses up to the
            // last 15 seconds and a finished episode never becomes Watched.
            scope.launch { withContext(NonCancellable) { viewModel.saveProgress() } }
            controller.release()
            scope.cancel()
        }
    }

    // The route names what was opened; once the player has moved on to a Next Episode the view
    // model holds what is playing, and a rebuilt screen picks that up instead. Keyed on the route
    // so a reused entry (a single-top navigation) opened on something new plays that.
    LaunchedEffect(mediaKey, streamUrl) {
        viewModel.attach(VodPlayerViewModel.Item(mediaKey, streamUrl, title, userAgent), playback)
        var sinceSaved = 0L
        while (isActive) {
            delay(POLL_MILLIS)
            viewModel.poll()
            sinceSaved += POLL_MILLIS
            if (sinceSaved >= SAVE_EVERY_MILLIS) {
                viewModel.saveProgress()
                sinceSaved = 0
            }
        }
    }

    // Sleep timer: leave the film when the armed deadline passes (position is saved on dispose).
    val sleepDeadline by SleepTimer.deadline.collectAsState()
    LaunchedEffect(sleepDeadline) {
        val d = sleepDeadline ?: return@LaunchedEffect
        val wait = d - System.currentTimeMillis()
        if (wait > 0) delay(wait)
        SleepTimer.clear()
        onBack()
    }

    VodPlayerContent(controller, viewModel, title, growingRec, onBack)
}

/**
 * The player's picture, transport bar and Next Episode card, with everything about playback that the
 * remote drives. [VodPlayerScreen] owns the stream, resume, saving progress and moving between
 * episodes; this owns what the viewer sees and presses.
 */
@OptIn(UnstableApi::class)
@Composable
fun VodPlayerContent(
    controller: PlayerController,
    viewModel: VodPlayerViewModel,
    title: String,
    growingRec: Boolean,
    onBack: () -> Unit,
) {
    // Skip within a recording that's still being written. ExoPlayer won't report it as seekable (no
    // fixed length), so we seek directly, clamped to what's on disk.
    fun seekWithinRecorded(deltaMs: Long) {
        val p = controller.player
        p.seekTo((p.currentPosition + deltaMs).coerceIn(0L, p.bufferedPosition.coerceAtLeast(0L)))
    }

    // The on-screen buttons and the remote's media keys both skip through here, so a recording that's
    // still being written stays inside what's on disk either way.
    fun skip(forward: Boolean) {
        val step = if (forward) PlayerController.SEEK_INCREMENT_MILLIS else -PlayerController.SEEK_INCREMENT_MILLIS
        when {
            growingRec -> seekWithinRecorded(step)
            forward -> controller.seekForward()
            else -> controller.seekBackward()
        }
    }
    val state by controller.state.collectAsState()
    val tracks by controller.tracks.collectAsState()
    val current by viewModel.current.collectAsState()
    val overlay by viewModel.overlay.collectAsState()
    // Whether the card or the question is up, not which second it shows: effects keyed on this
    // run when one appears or goes, rather than on every tick of the countdown.
    val overlayShowing = overlay !is NextEpisodeCountdown.Overlay.None

    val playWhenReady by controller.playWhenReady.collectAsState()
    // Nothing loaded yet isn't "paused": the player only means to play once a stream is set. Read
    // from the player, so it stays right when "Are you still watching?" pauses it or the next
    // episode starts.
    val paused = state != PlayerController.State.Idle && !playWhenReady
    var vodPanel by remember { mutableStateOf(VodPanel.NONE) }
    val panelFocus = remember { FocusRequester() }
    var positionMs by remember { mutableLongStateOf(0L) }
    var durationMs by remember { mutableLongStateOf(0L) }
    var scrubbing by remember { mutableStateOf(false) }
    var scrubValue by remember { mutableFloatStateOf(0f) }

    var controlsVisible by remember { mutableStateOf(true) }
    var interaction by remember { androidx.compose.runtime.mutableIntStateOf(0) }
    val barFocus = remember { FocusRequester() }
    val rootFocus = remember { FocusRequester() }

    fun reveal() {
        controlsVisible = true
        interaction++
    }

    // The Next Episode card sits where the transport bar would, and takes focus itself; a picker
    // left open behind it would come back stale with the bar.
    LaunchedEffect(overlayShowing) {
        if (overlayShowing) {
            controlsVisible = false
            vodPanel = VodPanel.NONE
        }
    }

    // Poll position/duration for the seek bar while the film plays.
    LaunchedEffect(Unit) {
        while (isActive) {
            if (!scrubbing) {
                positionMs = controller.player.currentPosition.coerceAtLeast(0)
                // A growing recording has no fixed length; the recorded-so-far extent (how far you
                // can skip ahead) is what's been read into the buffer.
                durationMs = if (growingRec) {
                    controller.player.bufferedPosition.coerceAtLeast(0)
                } else {
                    controller.player.duration.takeIf { it > 0 } ?: 0
                }
            }
            delay(500)
        }
    }

    // Auto-hide when playing and not paused/scrubbing and no picker open. A growing recording dips in
    // and out of Buffering as it rides the write head, so for that case Buffering counts as "playing"
    // here — otherwise a single hiccup would pin the transport bar on screen for the rest of the watch.
    LaunchedEffect(controlsVisible, interaction, state, paused, scrubbing, vodPanel) {
        val activelyPlaying = state is PlayerController.State.Playing ||
            (growingRec && state is PlayerController.State.Buffering)
        if (controlsVisible && !paused && !scrubbing && vodPanel == VodPanel.NONE && activelyPlaying) {
            delay(5_000)
            controlsVisible = false
        }
    }

    LaunchedEffect(controlsVisible, vodPanel, overlayShowing) {
        when {
            controlsVisible -> {
                delay(40)
                runCatching { if (vodPanel != VodPanel.NONE) panelFocus.requestFocus() else barFocus.requestFocus() }
            }
            !overlayShowing -> runCatching { rootFocus.requestFocus() }
        }
    }

    BackHandler {
        val activelyPlaying = state is PlayerController.State.Playing ||
            (growingRec && state is PlayerController.State.Buffering)
        when {
            vodPanel != VodPanel.NONE -> vodPanel = VodPanel.NONE
            controlsVisible && activelyPlaying && !paused -> controlsVisible = false
            else -> onBack()
        }
    }

    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black)
            .countsAsInput(viewModel)
            .onPreviewKeyEvent { event ->
                when {
                    event.key == Key.Back || event.key == Key.Escape -> false
                    // Keys belong to the card or the question while one is up, OK above all.
                    overlayShowing -> false
                    // Media keys act whether or not the bar is up, so they come before "reveal".
                    event.key == Key.MediaPlayPause || event.key == Key.MediaPlay || event.key == Key.MediaPause -> {
                        // A held key repeats; acting on each repeat would flick between paused and playing.
                        if (event.type == KeyEventType.KeyDown && event.nativeKeyEvent.repeatCount == 0) {
                            controller.player.playWhenReady = when (event.key) {
                                Key.MediaPlay -> true
                                Key.MediaPause -> false
                                else -> paused
                            }
                            reveal()
                        }
                        true
                    }
                    event.key == Key.MediaRewind || event.key == Key.MediaFastForward -> {
                        if (event.type == KeyEventType.KeyDown) {
                            skip(forward = event.key == Key.MediaFastForward)
                            interaction++
                        }
                        true
                    }
                    event.type == KeyEventType.KeyDown && !controlsVisible -> {
                        reveal()
                        true
                    }
                    event.type == KeyEventType.KeyDown -> {
                        interaction++
                        false
                    }
                    else -> false
                }
            }
            .focusRequester(rootFocus)
            .focusable()
            .pointerInput(Unit) {
                detectTapGestures { if (controlsVisible) controlsVisible = false else reveal() }
            },
    ) {
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { ctx ->
                PlayerView(ctx).apply {
                    player = controller.player
                    useController = false
                    subtitleView?.setUserDefaultStyle()
                    subtitleView?.setUserDefaultTextSize()
                    layoutParams = ViewGroup.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT,
                    )
                }
            },
        )

        when (val current = state) {
            is PlayerController.State.Buffering ->
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        CircularProgressIndicator()
                        Spacer(Modifier.height(16.dp))
                        Text(current.title, color = Color.White)
                    }
                }

            is PlayerController.State.Error ->
                Box(
                    Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.85f)),
                    contentAlignment = Alignment.Center,
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier.padding(48.dp),
                    ) {
                        Text(current.title, style = MaterialTheme.typography.headlineSmall, color = Color.White)
                        Spacer(Modifier.height(12.dp))
                        Text(current.message, color = Color.White.copy(alpha = 0.85f), textAlign = TextAlign.Center)
                        Spacer(Modifier.height(24.dp))
                        Button(onClick = { controller.retry() }) { Text(stringResource(R.string.common_try_again)) }
                    }
                }

            else -> Unit
        }

        AnimatedVisibility(
            visible = controlsVisible,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier.align(Alignment.BottomCenter),
        ) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.9f))))
                    // Keep the controls clear of the phone/tablet system nav bar (and the side nav
                    // bar / display cutout in landscape). Zero on a TV, so the 10-foot layout is
                    // unchanged; the gradient above stays full-bleed to the screen edge.
                    .windowInsetsPadding(
                        WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal + WindowInsetsSides.Bottom),
                    )
                    .padding(horizontal = 28.dp, vertical = 20.dp),
            ) {
                if (vodPanel != VodPanel.NONE) {
                    TrackPanel(
                        panel = vodPanel,
                        controller = controller,
                        tracks = tracks,
                        firstFocus = panelFocus,
                        onDone = {
                            vodPanel = VodPanel.NONE
                            interaction++
                        },
                    )
                    Spacer(Modifier.height(16.dp))
                }

                if (growingRec) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            Icons.Filled.FiberManualRecord,
                            contentDescription = null,
                            tint = Color(0xFFE53935),
                            modifier = Modifier.size(14.dp),
                        )
                        Spacer(Modifier.width(6.dp))
                        Text(
                            stringResource(R.string.rec_watching_live_badge),
                            style = MaterialTheme.typography.labelLarge,
                            color = Color(0xFFE53935),
                        )
                    }
                    Spacer(Modifier.height(4.dp))
                }
                Text(current?.title ?: title, style = MaterialTheme.typography.headlineSmall, color = Color.White)
                Spacer(Modifier.height(8.dp))

                // Seek bar with times either side. Left/right on the remote scrubs.
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(formatPlaybackTime(if (scrubbing) scrubValue.toLong() else positionMs), color = Color.White)
                    Spacer(Modifier.width(12.dp))
                    Slider(
                        value = if (scrubbing) scrubValue else positionMs.toFloat(),
                        valueRange = 0f..(durationMs.takeIf { it > 0 }?.toFloat() ?: 1f),
                        onValueChange = {
                            scrubbing = true
                            scrubValue = it
                            interaction++
                        },
                        onValueChangeFinished = {
                            controller.player.seekTo(scrubValue.toLong())
                            positionMs = scrubValue.toLong()
                            scrubbing = false
                            interaction++
                        },
                        modifier = Modifier.weight(1f),
                    )
                    Spacer(Modifier.width(12.dp))
                    Text(formatPlaybackTime(durationMs), color = Color.White)
                }

                Spacer(Modifier.height(10.dp))

                VodPlayerControls(
                    paused = paused,
                    onBack = onBack,
                    onRewind = {
                        skip(forward = false)
                        interaction++
                    },
                    onPlayPause = {
                        controller.player.playWhenReady = paused
                        interaction++
                    },
                    onForward = {
                        skip(forward = true)
                        interaction++
                    },
                    onSubtitles = {
                        vodPanel = if (vodPanel == VodPanel.SUBTITLES) VodPanel.NONE else VodPanel.SUBTITLES
                        interaction++
                    },
                    onAudio = {
                        vodPanel = if (vodPanel == VodPanel.AUDIO) VodPanel.NONE else VodPanel.AUDIO
                        interaction++
                    },
                    playPauseFocus = barFocus,
                )
            }
        }

        NextEpisodeOverlay(viewModel, onStop = onBack)
    }
}

private const val POLL_MILLIS = 500L

/** How often progress is written while playing, so a crash or power cut loses little. */
private const val SAVE_EVERY_MILLIS = 15_000L

/** The movie and episode player's transport row: Back, then rewind, play/pause, forward and the pickers. */
@Composable
fun VodPlayerControls(
    paused: Boolean,
    onBack: () -> Unit,
    onRewind: () -> Unit,
    onPlayPause: () -> Unit,
    onForward: () -> Unit,
    onSubtitles: () -> Unit,
    onAudio: () -> Unit,
    playPauseFocus: FocusRequester? = null,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        PlayerBackButton(onBack)
        Spacer(Modifier.width(20.dp))
        PlayerChip(Icons.Filled.FastRewind, stringResource(R.string.player_rewind), onClick = onRewind)
        Spacer(Modifier.width(10.dp))
        PlayerChip(
            icon = if (paused) Icons.Filled.PlayArrow else Icons.Filled.Pause,
            label = stringResource(if (paused) R.string.common_play else R.string.player_pause),
            focusRequester = playPauseFocus,
            onClick = onPlayPause,
        )
        Spacer(Modifier.width(10.dp))
        PlayerChip(Icons.Filled.FastForward, stringResource(R.string.player_forward), onClick = onForward)
        Spacer(Modifier.width(20.dp))
        PlayerChip(Icons.Filled.ClosedCaption, stringResource(R.string.player_subtitles), onClick = onSubtitles)
        Spacer(Modifier.width(10.dp))
        PlayerChip(Icons.Filled.Audiotrack, stringResource(R.string.player_audio), onClick = onAudio)
    }
}

private enum class VodPanel { NONE, SUBTITLES, AUDIO }

@OptIn(UnstableApi::class)
@Composable
private fun TrackPanel(
    panel: VodPanel,
    controller: PlayerController,
    tracks: Tracks,
    firstFocus: FocusRequester,
    onDone: () -> Unit,
) {
    val trackType = if (panel == VodPanel.SUBTITLES) C.TRACK_TYPE_TEXT else C.TRACK_TYPE_AUDIO
    val groups = tracks.groups.filter { it.type == trackType }
    val options = mutableListOf<Triple<String, Boolean, () -> Unit>>()

    if (panel == VodPanel.SUBTITLES) {
        val anySelected = groups.any { g -> (0 until g.length).any { g.isTrackSelected(it) } }
        options +=
            Triple(stringResource(R.string.player_subtitles_off), !anySelected) {
                controller.disableText()
                onDone()
            }
    }
    groups.forEach { group ->
        for (i in 0 until group.length) {
            if (!group.isTrackSupported(i)) continue
            val format = group.getTrackFormat(i)
            options += Triple(vodTrackLabel(format.label, format.language, options.size), group.isTrackSelected(i)) {
                controller.selectTrack(group, i)
                onDone()
            }
        }
    }

    Column(
        Modifier
            .widthIn(max = 420.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(Color.Black.copy(alpha = 0.92f))
            .padding(16.dp),
    ) {
        Text(
            if (panel ==
                VodPanel.SUBTITLES
            ) {
                stringResource(R.string.player_subtitles)
            } else {
                stringResource(R.string.player_audio)
            },
            style = MaterialTheme.typography.titleMedium,
            color = Color.White.copy(alpha = 0.7f),
        )
        Spacer(Modifier.height(8.dp))
        Column(Modifier.heightIn(max = 220.dp).verticalScroll(rememberScrollState())) {
            if (options.isEmpty()) {
                Text(
                    stringResource(R.string.player_none_available),
                    color = Color.White.copy(alpha = 0.6f),
                    modifier = Modifier.padding(8.dp),
                )
            }
            options.forEachIndexed { index, (label, selected, onClick) ->
                TrackRow(label, selected, onClick, if (index == 0) firstFocus else null)
            }
        }
    }
}

@Composable
private fun TrackRow(label: String, selected: Boolean, onClick: () -> Unit, focusRequester: FocusRequester?) {
    var focused by remember { mutableStateOf(false) }
    val bg = if (focused) MaterialTheme.colorScheme.primary else Color.White.copy(alpha = 0.08f)
    val fg = if (focused) MaterialTheme.colorScheme.onPrimary else Color.White
    Row(
        Modifier
            .fillMaxWidth()
            .then(if (focusRequester != null) Modifier.focusRequester(focusRequester) else Modifier)
            .onFocusChanged { focused = it.isFocused }
            .clip(RoundedCornerShape(10.dp))
            .background(bg)
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, style = MaterialTheme.typography.titleMedium, color = fg, modifier = Modifier.weight(1f))
        if (selected) Icon(Icons.Filled.Check, contentDescription = stringResource(R.string.common_selected), tint = fg)
    }
}

private fun vodTrackLabel(label: String?, language: String?, index: Int): String {
    if (!label.isNullOrBlank()) return label
    if (!language.isNullOrBlank() && language != "und") {
        return runCatching { java.util.Locale(language).displayLanguage.ifBlank { language } }.getOrDefault(language)
    }
    return "Track ${index + 1}"
}
