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
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsDraggedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bedtime
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.FiberManualRecord
import androidx.compose.material.icons.filled.HighQuality
import androidx.compose.material.icons.filled.MoreHoriz
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
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
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
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
import app.opentv.core.findActivity
import app.opentv.data.repo.MediaKey
import app.opentv.player.NextEpisodeCountdown
import app.opentv.player.PlayerController
import app.opentv.ui.design.AerialTv
import app.opentv.ui.design.Icon
import app.opentv.ui.design.SetteeColors
import app.opentv.ui.design.SetteeType
import app.opentv.ui.design.Text
import app.opentv.ui.options.ItemOption
import app.opentv.ui.options.OptionsFor
import app.opentv.ui.options.label
import app.opentv.ui.player.BackOnRelease
import app.opentv.ui.player.PlayerChoice
import app.opentv.ui.player.PlayerChoicePanel
import app.opentv.ui.player.PlayerControlBar
import app.opentv.ui.player.PlayerNotice
import app.opentv.ui.player.PlayerOptions
import app.opentv.ui.player.PlayerPill
import app.opentv.ui.player.SleepTimerEffect
import app.opentv.ui.player.SleepTimerPanel
import app.opentv.ui.player.SubtitlePicks
import app.opentv.ui.player.WhenBehindAnotherApp
import app.opentv.ui.player.backOnRelease
import app.opentv.ui.player.isBack
import app.opentv.ui.player.trackLabel
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

    // Null in a build without Settee's OpenSubtitles key: then the subtitle list is only the stream's.
    val vodSubtitles = remember {
        graph.openSubtitles?.let {
            VodSubtitles(it, graph.profileSettings, scope, streamTextOff = controller::disableText)
        }
    }
    val subtitlePicks = rememberVodProfileSubtitles(controller, graph.profileSettings, scope, vodSubtitles)

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

    val finished by viewModel.finished.collectAsState()
    LaunchedEffect(finished) { if (finished) onBack() }

    CompositionLocalProvider(LocalVodSubtitles provides vodSubtitles) {
        VodPlayerContent(controller, viewModel, growingRec, subtitlePicks, onBack)
    }
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

    // Not the switching placeholder above, nor the player's duration: a stream whose length isn't known
    // still has a place. A request from 0 is unknown rather than 0, so another Version starts at its
    // own resume point rather than at the beginning.
    override fun position() = if (isSwitching) {
        positionMillis.takeIf { it > 0 }
    } else {
        player.currentPosition.coerceAtLeast(0)
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
 * episodes; this owns what the viewer sees and presses, and pausing behind another app.
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
    // Leave the film when the sleep timer runs out (VodPlayerScreen saves the position on dispose).
    SleepTimerEffect(onRunOut = onBack)
    val seek = remember { UnfinishedSeek() }
    // OpenSubtitles' part of the subtitle list; null without Settee's key.
    val subtitles = LocalVodSubtitles.current

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
    var choosingSleep by remember { mutableStateOf(false) }
    var choosingQuality by remember { mutableStateOf(false) }
    val qualityChoices by viewModel.qualityChoices.collectAsState()
    val canMarkWatched by viewModel.canMarkWatched.collectAsState()
    val marking = rememberCoroutineScope()
    val steps by viewModel.episodeSteps.collectAsState()

    // The pill and the options list both mark it; marking it leaves the player.
    fun markWatched() {
        marking.launch { if (viewModel.markWatched()) onBack() }
    }

    // Behind another app it pauses and keeps its place, as leaving the player does, and coming back it
    // stays paused: the viewer may have been away for hours, and presses Play when they're watching.
    // A recording pauses the same way; RecordingEngine goes on recording it.
    WhenBehindAnotherApp(
        onGoneBehind = {
            controller.behindAnotherApp = true
            marking.launch { withContext(NonCancellable) { viewModel.saveProgress() } }
        },
        onBack = { controller.behindAnotherApp = false },
    )
    val panelFocus = remember { FocusRequester() }
    // Where the Highlight was on the controls, for a panel closing to give it back to.
    val barReturn = remember { FocusRequester() }
    var positionMs by remember { mutableLongStateOf(0L) }
    var durationMs by remember { mutableLongStateOf(0L) }
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

    // The episode buttons the row shows. A new episode's come at once; ones found late for the same
    // episode (its series loading beside playback) wait while the controls show, so a button never
    // appears beside the Highlight while the viewer is moving along the row.
    var shownSteps by remember { mutableStateOf(steps) }
    LaunchedEffect(steps, controlsVisible) {
        if (!controlsVisible || steps.of != shownSteps.of) shownSteps = steps
    }

    fun moveEpisode(forward: Boolean) {
        marking.launch { if (forward) viewModel.toNextEpisode() else viewModel.toPreviousEpisode() }
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
        subtitles?.load(playing)
    }

    // A bar picked up and left alone goes back to where it started. Not tied to the controls hiding:
    // a paused film never hides, and the Next Episode card can take the Highlight from a picked-up
    // bar. A held key repeats, so it counts as activity; a finger can rest without moving.
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
    LaunchedEffect(controlsVisible, interaction, state, paused, vodPanel, dragging, optionsOpen, choosingSleep) {
        val activelyPlaying = state is PlayerController.State.Playing ||
            (growingRec && state is PlayerController.State.Buffering)
        val watching = activelyPlaying && !paused && !dragging
        val inUse = vodPanel != VodPanel.NONE || optionsOpen || choosingSleep
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
                runCatching { if (vodPanel != VodPanel.NONE) panelFocus.requestFocus() else barReturn.requestFocus() }
            }
            !overlayShowing -> runCatching { rootFocus.requestFocus() }
        }
    }

    fun backStep(): () -> Unit {
        val activelyPlaying = state is PlayerController.State.Playing ||
            (growingRec && state is PlayerController.State.Buffering)
        return when {
            // The bar and any picker on it are hidden behind the sentence, so Back can't mean them.
            failed -> onBack
            vodPanel != VodPanel.NONE -> {
                { vodPanel = VodPanel.NONE }
            }
            controlsVisible && activelyPlaying && !paused -> {
                { controlsVisible = false }
            }
            else -> onBack
        }
    }

    BackHandler { backStep()() }
    val backOnRelease = remember { BackOnRelease() }

    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black)
            .countsAsInput(viewModel)
            // The Next Episode card and its question answer Back with back handlers of their own.
            .backOnRelease(backOnRelease) { if (overlayShowing) null else backStep() }
            .onPreviewKeyEvent { event ->
                when {
                    event.isBack() -> false
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
                    event.key == Key.MediaNext || event.key == Key.MediaPrevious -> {
                        if (event.type == KeyEventType.KeyDown && event.nativeKeyEvent.repeatCount == 0) {
                            moveEpisode(forward = event.key == Key.MediaNext)
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

        subtitles?.let { DownloadedSubtitleOverlay(it, positionMillis = { controller.positionMillis }) }

        val notice by controller.notice.collectAsState()
        notice?.let { PlayerNotice(it, Modifier.align(Alignment.TopCenter)) }
        subtitles?.let { SubtitleNote(it, Modifier.align(Alignment.TopStart)) }

        when (val current = state) {
            // Which Quality to play is still being worked out, which may mean asking the provider. Idle
            // once something has played means it finished or was stopped, with nothing to wait for.
            PlayerController.State.Idle -> if (playingKey == null) {
                AerialTv(stringResource(R.string.player_loading), Modifier.align(Alignment.Center))
            }

            is PlayerController.State.Buffering ->
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        AerialTv(stringResource(R.string.player_loading))
                        Spacer(Modifier.height(16.dp))
                        Text(current.title, SetteeType.Body)
                    }
                }

            is PlayerController.State.Error -> PlayerErrorPanel(current, onRetry = controller::retry, onBack = onBack)

            else -> Unit
        }

        AnimatedVisibility(visible = controlsVisible && !failed, enter = fadeIn(), exit = fadeOut()) {
            PlayerControlBar(
                title = title,
                paused = paused,
                positionMs = seek.position?.toLong() ?: positionMs,
                durationMs = durationMs,
                seekPickedUp = seek.position != null,
                onPickUp = {
                    seek.pickUp(at = positionMs.toFloat())
                    interaction++
                },
                onScrub = { to ->
                    seek.pressed(to = to.toFloat())
                    interaction++
                },
                onPutDown = { jump ->
                    val target = seek.putDown()
                    if (jump && target != null) {
                        controller.player.seekTo(target)
                        positionMs = target
                    }
                    interaction++
                },
                onPlayPause = {
                    controller.player.playWhenReady = paused
                    interaction++
                },
                onSubtitles = {
                    vodPanel = VodPanel.SUBTITLES
                    interaction++
                },
                onAudio = {
                    vodPanel = VodPanel.AUDIO
                    interaction++
                },
                onClose = onBack,
                pills = vodPills(
                    options = OptionsFor.VodPlayer(canMarkWatched).options(),
                    onMarkWatched = ::markWatched,
                    onSleepTimer = { choosingSleep = true },
                    onOptions = { optionsOpen = true },
                    onQuality = { choosingQuality = true }.takeIf { qualityChoices.size > 1 },
                ),
                startFocus = barFocus,
                returnFocus = barReturn,
                onPreviousEpisode = { moveEpisode(forward = false) }.takeIf { shownSteps.previous },
                onNextEpisode = { moveEpisode(forward = true) }.takeIf { shownSteps.next },
                barInteractions = barInteractions,
                badge = if (growingRec) ({ RecordingBadge() }) else null,
            )
        }

        val panelShowing = vodPanel != VodPanel.NONE && controlsVisible && !failed
        if (panelShowing && vodPanel == VodPanel.OPENSUBTITLES && subtitles != null) {
            OpenSubtitlesResultsPanel(
                subtitles,
                firstFocus = panelFocus,
                onClose = {
                    vodPanel = VodPanel.SUBTITLES
                    interaction++
                },
                onPicked = {
                    vodPanel = VodPanel.NONE
                    interaction++
                },
                modifier = Modifier.align(Alignment.CenterEnd),
            )
        } else if (panelShowing) {
            TrackPanel(
                panel = vodPanel,
                controller = controller,
                tracks = tracks,
                subtitlePicks = subtitlePicks,
                subtitles = subtitles,
                onFind = {
                    subtitles?.search()
                    vodPanel = VodPanel.OPENSUBTITLES
                    interaction++
                },
                firstFocus = panelFocus,
                onDone = {
                    vodPanel = VodPanel.NONE
                    interaction++
                },
                modifier = Modifier.align(Alignment.CenterEnd),
            )
        }

        NextEpisodeOverlay(viewModel, onStop = onBack)
    }

    if (choosingSleep) SleepTimerPanel(onDone = { choosingSleep = false })

    if (choosingQuality) {
        QualityPanel(
            qualityChoices,
            onChoose = { version ->
                choosingQuality = false
                marking.launch { viewModel.chooseVersion(version) }
            },
            onDismiss = { choosingQuality = false },
        )
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
                    ItemOption.MarkWatched -> markWatched()
                    else -> Unit
                }
            },
            onDismiss = { optionsOpen = false },
        )
    }
}

private const val POLL_MILLIS = 500L

/** How long a picked-up bar waits for a press before going back. To adjust once tried on the Stick. */
private const val UNFINISHED_SEEK_GRACE_MILLIS = 3_000L

/** How often progress is written while playing, so a crash or power cut loses little. */
private const val SAVE_EVERY_MILLIS = 15_000L

/**
 * The pills under the row: Quality when the title's Versions differ ([onQuality] is null when they
 * don't), [OptionsFor.VodPlayer]'s options, less Subtitles and Audio, which have their own buttons on the
 * row, then Options for the whole list (what Menu opens).
 */
@Composable
private fun vodPills(
    options: List<ItemOption>,
    onMarkWatched: () -> Unit,
    onSleepTimer: () -> Unit,
    onOptions: () -> Unit,
    onQuality: (() -> Unit)?,
): List<PlayerPill> = buildList {
    onQuality?.let { add(PlayerPill(stringResource(R.string.vod_quality), Icons.Filled.HighQuality, it)) }
    options.forEach { option ->
        when (option) {
            ItemOption.MarkWatched -> add(PlayerPill(option.label(), Icons.Filled.Check, onMarkWatched))
            ItemOption.SleepTimer -> add(PlayerPill(option.label(), Icons.Filled.Bedtime, onSleepTimer))
            else -> Unit
        }
    }
    add(PlayerPill(stringResource(R.string.player_options), Icons.Filled.MoreHoriz, onOptions))
}

/** Shown over the title while the recording being watched is still being written. */
@Composable
private fun RecordingBadge() {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(bottom = 4.dp)) {
        Icon(
            Icons.Filled.FiberManualRecord,
            contentDescription = null,
            tint = SetteeColors.Recording,
            modifier = Modifier.size(14.dp),
        )
        Spacer(Modifier.width(6.dp))
        Text(stringResource(R.string.rec_watching_live_badge), SetteeType.Body, color = SetteeColors.Recording)
    }
}

private enum class VodPanel { NONE, SUBTITLES, AUDIO, OPENSUBTITLES }

/**
 * Subtitles or audio, in the player's choice panel. With OpenSubtitles ([subtitles]), the subtitle
 * list starts with its rows (see [openSubtitlesChoices]): its entry, Timing while a Downloaded Subtitle
 * shows, then the title's Downloaded Subtitles, before Off and the stream's own tracks.
 */
@OptIn(UnstableApi::class)
@Composable
private fun TrackPanel(
    panel: VodPanel,
    controller: PlayerController,
    tracks: Tracks,
    subtitlePicks: SubtitlePicks,
    subtitles: VodSubtitles?,
    onFind: () -> Unit,
    firstFocus: FocusRequester,
    onDone: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val trackType = if (panel == VodPanel.SUBTITLES) C.TRACK_TYPE_TEXT else C.TRACK_TYPE_AUDIO
    val groups = tracks.groups.filter { it.type == trackType }
    val options = mutableListOf<PlayerChoice>()
    val above = if (panel == VodPanel.SUBTITLES && subtitles != null) {
        openSubtitlesChoices(subtitles, onFind, onDone)
    } else {
        emptyList()
    }
    // A Downloaded Subtitle is drawn by the screen with the stream's text off, so none of the
    // stream's tracks counts as chosen while one shows.
    val downloadedShowing = subtitles?.showing?.collectAsState()?.value != null

    if (panel == VodPanel.SUBTITLES) {
        val anySelected = downloadedShowing || groups.any { g -> (0 until g.length).any { g.isTrackSelected(it) } }
        options +=
            PlayerChoice(stringResource(R.string.player_subtitles_off), !anySelected, {
                controller.disableText()
                subtitlePicks.onOff()
                onDone()
            })
    }
    groups.forEach { group ->
        for (i in 0 until group.length) {
            if (!group.isTrackSupported(i)) continue
            val format = group.getTrackFormat(i)
            val selected = group.isTrackSelected(i) && !(trackType == C.TRACK_TYPE_TEXT && downloadedShowing)
            options += PlayerChoice(trackLabel(format.label, format.language, options.size), selected, {
                controller.selectTrack(group, i)
                if (trackType == C.TRACK_TYPE_TEXT) subtitlePicks.onPicked(format.language)
                onDone()
            })
        }
    }
    PlayerChoicePanel(
        title = stringResource(if (panel == VodPanel.SUBTITLES) R.string.player_subtitles else R.string.player_audio),
        choices = above + options,
        startFocus = firstFocus,
        onClose = onDone,
        modifier = modifier,
    )
}
