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
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsDraggedAsState
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
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
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
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusDirection
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
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
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
import app.opentv.data.repo.MediaKey
import app.opentv.player.NextEpisodeCountdown
import app.opentv.player.PlayerController
import app.opentv.ui.formatPlaybackTime
import app.opentv.ui.options.ItemOption
import app.opentv.ui.options.OptionsFor
import app.opentv.ui.player.PlayerBackButton
import app.opentv.ui.player.PlayerChip
import app.opentv.ui.player.PlayerNotice
import app.opentv.ui.player.PlayerOptions
import app.opentv.ui.player.SubtitlePicks
import app.opentv.ui.player.rememberProfileSubtitles
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
    mediaKey: MediaKey,
    onBack: () -> Unit,
    fromStart: Boolean = false,
    viewModel: VodPlayerViewModel = viewModel(),
) {
    val context = LocalContext.current
    val view = LocalView.current
    val graph = remember { ServiceLocator.get(context) }
    val scope = remember { CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate) }
    // A recording that's still being written plays through the tail-following source. It behaves a
    // little differently in the transport: the "length" is how much has been recorded so far, which
    // keeps growing, and seeking is bounded to that recorded extent.
    // The player is built for it, so it waits until the recording's row says whether it is.
    val growing by produceState<Boolean?>(null, mediaKey) { value = graph.playbackSources.isGrowing(mediaKey) }
    val growingRec = growing ?: return
    // The SMB source lets a recording stored on a NAS play and seek in-app; harmless for the
    // http/file URLs of ordinary VOD.
    val controller = remember {
        PlayerController(
            context,
            scope,
            graph.streamingHttpClient,
            resolve = graph.playbackSources::forWatching,
            PlayerController.Options(
                // Text starts off; the Profile's Subtitle Preference turns it on once tracks arrive.
                subtitlesEnabled = false,
                smbDataSourceFactory = app.opentv.player.SmbDataSource.Factory(graph.settings),
                // Lets an `optvrec://<id>` recording play while it's still being written.
                growingDataSourceFactory =
                app.opentv.player.GrowingRecordingDataSource.Factory(context.applicationContext),
                liveRecording = growingRec,
            ),
        )
    }

    val playback = remember { controller.vodPlayback() }

    val subtitlePicks = rememberProfileSubtitles(controller, graph.profileSettings, scope)

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
    LaunchedEffect(mediaKey) {
        viewModel.attach(mediaKey, playback, fromStart)
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

    val finished by viewModel.finished.collectAsState()
    LaunchedEffect(finished) { if (finished) onBack() }

    VodPlayerContent(controller, viewModel, growingRec, subtitlePicks, onBack)
}

/** [this] as the view model drives it. */
@OptIn(UnstableApi::class)
internal fun PlayerController.vodPlayback() = object : VodPlayerViewModel.Playback {
    // Finding a stream can take a while on a portal, and until then the player still reports the
    // last title: saved now, that position would land on the title moved on to.
    override fun progress() = if (isSwitching) {
        NextEpisodeCountdown.Playback(positionMillis = 0, durationMillis = 0)
    } else {
        player.let { p ->
            NextEpisodeCountdown.Playback(
                positionMillis = p.currentPosition.coerceAtLeast(0),
                durationMillis = p.duration.takeIf { it > 0 } ?: 0,
                ended = p.playbackState == Player.STATE_ENDED,
            )
        }
    }

    // Swapping the media item in the same player, never a new one: see PlayerController.
    override fun play(request: PlayerController.Request, skipped: String?) =
        this@vodPlayback.play(request, debounce = false, skipped = skipped)

    override fun steppedDownTo() = this@vodPlayback.steppedDownTo.value

    override fun pause() {
        player.playWhenReady = false
    }

    override fun stop() = this@vodPlayback.stop()
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
    growingRec: Boolean,
    subtitlePicks: SubtitlePicks,
    onBack: () -> Unit,
) {
    val seek = remember { UnfinishedSeek() }

    // Skip within a recording that's still being written. ExoPlayer won't report it as seekable (no
    // fixed length), so we seek directly, clamped to what's on disk.
    fun seekWithinRecorded(deltaMs: Long) {
        val p = controller.player
        p.seekTo((p.currentPosition + deltaMs).coerceIn(0L, p.bufferedPosition.coerceAtLeast(0L)))
    }

    // The on-screen buttons and the remote's media keys both skip through here, so a recording that's
    // still being written stays inside what's on disk either way.
    fun skip(forward: Boolean) {
        // The latest thing asked for wins: the seek's key-up would otherwise undo this skip.
        seek.drop()
        val step = if (forward) PlayerController.SEEK_INCREMENT_MILLIS else -PlayerController.SEEK_INCREMENT_MILLIS
        when {
            growingRec -> seekWithinRecorded(step)
            forward -> controller.seekForward()
            else -> controller.seekBackward()
        }
    }
    val state by controller.state.collectAsState()
    val tracks by controller.tracks.collectAsState()
    val title by controller.title.collectAsState()
    val overlay by viewModel.overlay.collectAsState()
    val playingKey by viewModel.current.collectAsState()
    // Whether the card or the question is up, not which second it shows: effects keyed on this
    // run when one appears or goes, rather than on every tick of the countdown.
    val overlayShowing = overlay !is NextEpisodeCountdown.Overlay.None

    val playWhenReady by controller.playWhenReady.collectAsState()
    // Nothing loaded yet isn't "paused": the player only means to play once a stream is set. Read
    // from the player, so it stays right when "Are you still watching?" pauses it or the next
    // episode starts.
    val paused = state != PlayerController.State.Idle && !playWhenReady
    var vodPanel by remember { mutableStateOf(VodPanel.NONE) }
    var optionsOpen by remember { mutableStateOf(false) }
    val canMarkWatched by viewModel.canMarkWatched.collectAsState()
    val marking = rememberCoroutineScope()
    val panelFocus = remember { FocusRequester() }
    var positionMs by remember { mutableLongStateOf(0L) }
    var durationMs by remember { mutableLongStateOf(0L) }
    val focusManager = LocalFocusManager.current
    val barInteractions = remember { MutableInteractionSource() }
    val dragging by barInteractions.collectIsDraggedAsState()

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
            optionsOpen = false
        }
    }

    // Poll position/duration for the seek bar while the film plays.
    LaunchedEffect(Unit) {
        while (isActive) {
            positionMs = controller.positionMillis
            // Held still during a seek: a new length rescales the bar under the viewer's finger.
            if (seek.position == null) {
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

    // A seek through the last episode means nothing in this one, so it gets no grace.
    val playing by viewModel.current.collectAsState()
    LaunchedEffect(playing) {
        seek.drop()
        positionMs = controller.positionMillis
    }

    // Not tied to the bar losing focus or the controls hiding: a key-up can go missing with the bar
    // still focused and a paused film never hides, and neither may leave the bar stuck. A held key
    // repeats, so it counts as activity; a finger can rest without moving, but can't get lost.
    LaunchedEffect(seek.activity, dragging) {
        if (seek.position != null && !dragging) {
            delay(UNFINISHED_SEEK_GRACE_MILLIS)
            seek.drop()
        }
    }

    // Auto-hide when playing and not paused and no picker open, and no finger on the bar. A growing
    // recording dips in and out of Buffering as it rides the write head, so for that case Buffering
    // counts as "playing" here — otherwise a single hiccup would pin the transport bar on screen for
    // the rest of the watch.
    LaunchedEffect(controlsVisible, interaction, state, paused, vodPanel, dragging, optionsOpen) {
        val activelyPlaying = state is PlayerController.State.Playing ||
            (growingRec && state is PlayerController.State.Buffering)
        val watching = activelyPlaying && !paused && !dragging
        val inUse = vodPanel != VodPanel.NONE || optionsOpen
        if (controlsVisible && watching && !inUse) {
            delay(5_000)
            controlsVisible = false
        }
    }

    val failed = state is PlayerController.State.Error

    LaunchedEffect(controlsVisible, vodPanel, overlayShowing, failed) {
        when {
            // The sentence's own buttons take focus, and there's nothing under them to control.
            failed -> Unit
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
            // The bar and any picker on it are hidden behind the sentence, so Back can't mean them.
            failed -> onBack()
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
                    failed -> false
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
                    event.key == Key.Menu && vodPanel == VodPanel.NONE -> {
                        if (event.type == KeyEventType.KeyDown) optionsOpen = true
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

        val notice by controller.notice.collectAsState()
        notice?.let { PlayerNotice(it, Modifier.align(Alignment.TopCenter)) }

        when (val current = state) {
            // Which Quality to play is still being worked out, which may mean asking the provider. Idle
            // once something has played means it finished or was stopped, with nothing to wait for.
            PlayerController.State.Idle -> if (playingKey == null) {
                CircularProgressIndicator(Modifier.align(Alignment.Center))
            }

            is PlayerController.State.Buffering ->
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        CircularProgressIndicator()
                        Spacer(Modifier.height(16.dp))
                        Text(current.title, color = Color.White)
                    }
                }

            is PlayerController.State.Error -> PlayerErrorPanel(current, onRetry = controller::retry, onBack = onBack)

            else -> Unit
        }

        AnimatedVisibility(
            visible = controlsVisible && !failed,
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
                        subtitlePicks = subtitlePicks,
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
                Text(title, style = MaterialTheme.typography.headlineSmall, color = Color.White)
                Spacer(Modifier.height(8.dp))

                // Seek bar with times either side. Left/right on the remote scrubs.
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(formatPlaybackTime(seek.position?.toLong() ?: positionMs), color = Color.White)
                    Spacer(Modifier.width(12.dp))
                    Slider(
                        value = seek.position ?: positionMs.toFloat(),
                        valueRange = 0f..(durationMs.takeIf { it > 0 }?.toFloat() ?: 1f),
                        onValueChange = {
                            seek.pressed(to = it)
                            interaction++
                        },
                        onValueChangeFinished = {
                            seek.released()?.let { target ->
                                controller.player.seekTo(target)
                                positionMs = target
                            }
                            interaction++
                        },
                        interactionSource = barInteractions,
                        modifier = Modifier
                            .weight(1f)
                            .onFocusChanged { if (it.hasFocus) seek.reached() else seek.left() }
                            // The slider would seek on these too, and focus could never leave the bar.
                            // Down goes to play/pause by name: the row is searched from the whole
                            // width of the bar, and the nearest button could be Back.
                            .onPreviewKeyEvent { event ->
                                val pressed = event.type == KeyEventType.KeyDown
                                when (event.key) {
                                    Key.DirectionUp -> {
                                        if (pressed) focusManager.moveFocus(FocusDirection.Up)
                                        true
                                    }
                                    Key.DirectionDown -> {
                                        if (pressed) barFocus.requestFocus()
                                        true
                                    }
                                    else -> false
                                }
                            },
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
                    onOptions = { optionsOpen = true },
                    playPauseFocus = barFocus,
                )
            }
        }

        NextEpisodeOverlay(viewModel, onStop = onBack)
    }

    if (optionsOpen) {
        PlayerOptions(
            options = OptionsFor.VodPlayer(canMarkWatched).options(),
            onChoose = { option ->
                optionsOpen = false
                when (option) {
                    ItemOption.Subtitles -> {
                        reveal()
                        vodPanel = VodPanel.SUBTITLES
                    }
                    ItemOption.Audio -> {
                        reveal()
                        vodPanel = VodPanel.AUDIO
                    }
                    ItemOption.MarkWatched -> marking.launch { if (viewModel.markWatched()) onBack() }
                    else -> Unit
                }
            },
            onDismiss = { optionsOpen = false },
        )
    }
}

private const val POLL_MILLIS = 500L

/** How long an unfinished seek waits for the viewer to come back to the bar. To adjust once tried on the Stick. */
private const val UNFINISHED_SEEK_GRACE_MILLIS = 3_000L

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
    onOptions: () -> Unit,
    playPauseFocus: FocusRequester? = null,
) {
    // Fits a TV, but not a phone: scrolling keeps the last buttons reachable.
    Row(Modifier.horizontalScroll(rememberScrollState()), verticalAlignment = Alignment.CenterVertically) {
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
        Spacer(Modifier.width(10.dp))
        PlayerChip(Icons.Filled.MoreVert, stringResource(R.string.player_options), onClick = onOptions)
    }
}

private enum class VodPanel { NONE, SUBTITLES, AUDIO }

@OptIn(UnstableApi::class)
@Composable
private fun TrackPanel(
    panel: VodPanel,
    controller: PlayerController,
    tracks: Tracks,
    subtitlePicks: SubtitlePicks,
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
                subtitlePicks.onOff()
                onDone()
            }
    }
    groups.forEach { group ->
        for (i in 0 until group.length) {
            if (!group.isTrackSupported(i)) continue
            val format = group.getTrackFormat(i)
            options += Triple(vodTrackLabel(format.label, format.language, options.size), group.isTrackSelected(i)) {
                controller.selectTrack(group, i)
                if (trackType == C.TRACK_TYPE_TEXT) subtitlePicks.onPicked(format.language)
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
