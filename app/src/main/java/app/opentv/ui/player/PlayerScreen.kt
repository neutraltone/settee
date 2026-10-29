/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv.ui.player

import android.view.ViewGroup
import android.view.WindowManager
import androidx.activity.compose.BackHandler
import androidx.annotation.OptIn
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxHeight
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AspectRatio
import androidx.compose.material.icons.filled.Audiotrack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.FastForward
import androidx.compose.material.icons.filled.FastRewind
import androidx.compose.material.icons.filled.FiberManualRecord
import androidx.compose.material.icons.filled.HighQuality
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PictureInPictureAlt
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Subtitles
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.media3.common.C
import androidx.media3.common.Tracks
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import app.opentv.R
import app.opentv.core.ServiceLocator
import app.opentv.core.SleepTimer
import app.opentv.core.findActivity
import app.opentv.core.requestIgnoreBatteryOptimizations
import app.opentv.data.model.Channel
import app.opentv.data.model.shownName
import app.opentv.player.PlaybackQueue
import app.opentv.player.PlayerController
import app.opentv.ui.RecordingBackgroundDialog
import app.opentv.ui.RecordingBackgroundPrompt
import app.opentv.ui.formatPlaybackTime
import app.opentv.update.startsFocused
import coil.compose.AsyncImage
import java.util.Locale
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Full-screen live playback.
 *
 * The video fills the screen with no chrome. A single control bar slides up from the bottom and
 * holds everything — transport (play/pause, rewind, forward), and pickers for subtitles, audio,
 * quality and aspect ratio. It hides after a few seconds and any remote button brings it back.
 * Nothing is ever left permanently painted over the picture.
 *
 * Subtitles and audio come from the actual tracks in the stream ([PlayerController.tracks]) and
 * are selected explicitly — that is the fix for "captions on but nothing shows", which happens
 * when the renderer is merely enabled and left to guess a language.
 */
@OptIn(UnstableApi::class)
@Composable
fun PlayerScreen(channelId: Long?, onBack: () -> Unit) {
    val context = LocalContext.current
    val view = LocalView.current
    val graph = remember { ServiceLocator.get(context) }
    val settings = remember { graph.settings }
    val scope = remember { CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate) }
    val subtitlesDefault by settings.subtitlesEnabled.collectAsState()
    val livePause by settings.livePauseEnabled.collectAsState()
    val pausePromptDismissed by settings.livePausePromptDismissed.collectAsState()
    // Rebuilt only when live pause is switched on from the player: the DVR buffer is fixed when the
    // player is built. Changing channel never rebuilds it (see PlayerController).
    val controller = remember(livePause) {
        PlayerController(
            context,
            scope,
            graph.streamingHttpClient,
            subtitlesEnabled = settings.subtitlesEnabled.value,
            // Opt-in shallow DVR so the transport's pause/rewind actually holds on a live stream.
            dvr = livePause,
        )
    }
    DisposableEffect(controller) { onDispose { controller.release() } }
    val tracks by controller.tracks.collectAsState()
    // What's recording right now, so the Record button can show as armed for this channel.
    val activeRecordings by graph.recordingRepository.observeActive().collectAsState(initial = emptyList())

    // Hold the screen awake while the player is on screen. A view-level keepScreenOn flag isn't
    // reliable on every TV box, so we set the window flag on the Activity directly — that's what
    // actually stops the system screensaver from firing mid-programme. keepScreenOn stays on too,
    // as a belt-and-braces backstop.
    DisposableEffect(Unit) {
        val window = context.findActivity()?.window
        window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        view.keepScreenOn = true
        onDispose {
            window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            view.keepScreenOn = false
            scope.cancel()
        }
    }

    var variants by remember { mutableStateOf<List<Channel>>(emptyList()) }
    var currentId by remember { mutableStateOf<Long?>(null) }
    // The channel we were on before this one — powers the "Last channel" recall in the list.
    var previousId by remember { mutableStateOf<Long?>(null) }
    // Offered once per session the first time the user records here while OpenTV isn't exempt from
    // battery optimisation, so the capture survives the screen sleeping. Never blocks recording.
    var showBackgroundPrompt by remember { mutableStateOf(false) }

    DisposableEffect(Unit) {
        app.opentv.core.PipState.eligible = true
        onDispose {
            app.opentv.core.PipState.eligible = false
            app.opentv.core.PipState.isPlaying = false
        }
    }

    fun tuneTo(channel: Channel) {
        currentId = channel.id
        settings.lastChannelId = channel.id
        scope.launch {
            val source = graph.sourceRepository.byId(channel.sourceId)
            // Xtream/M3U carry a ready URL; a Stalker channel's URL is minted here from its cmd.
            val url = graph.catalogRepository.resolvePlaybackUrl(channel, source)
            controller.play(
                PlayerController.Request(
                    url = url,
                    title = channel.shownName,
                    userAgent = source?.userAgent ?: "OpenTV/0.1 (Android)",
                    isLive = true,
                ),
                debounce = false,
            )
        }
    }

    fun playChannelId(id: Long) {
        // Remember where we came from so "Last channel" can bounce straight back. Quality switches
        // go through tuneTo directly, so they never count as a channel change here.
        currentId?.let { if (it != id) previousId = it }
        scope.launch {
            val channel = graph.catalogRepository.channel(id) ?: return@launch
            variants = graph.catalogRepository.variants(channel)
            tuneTo(variants.firstOrNull { it.id == channel.id } ?: channel)
        }
    }

    fun toggleRecord() {
        val active = activeRecordings.firstOrNull { it.channelId == currentId }
        if (active != null) {
            graph.recordingEngine.stop(active.id)
        } else {
            val channel = variants.firstOrNull { it.id == currentId } ?: return
            scope.launch { graph.recordingEngine.startChannel(channel) }
            if (RecordingBackgroundPrompt.shouldShow(context)) {
                RecordingBackgroundPrompt.markShown()
                showBackgroundPrompt = true
            }
        }
    }

    LaunchedEffect(channelId) {
        val id = channelId ?: return@LaunchedEffect
        val channel = graph.catalogRepository.channel(id) ?: return@LaunchedEffect
        variants = graph.catalogRepository.variants(channel)
        tuneTo(variants.firstOrNull { it.id == channel.id } ?: channel)
    }
    // Switching live pause on rebuilds the controller; pick the channel straight back up on it.
    LaunchedEffect(controller) {
        if (controller.state.value == PlayerController.State.Idle) {
            variants.firstOrNull { it.id == currentId }?.let { tuneTo(it) }
        }
    }

    // Sleep timer: when the armed deadline passes, stop and leave the player. Re-arming from
    // settings restarts this effect with the new deadline.
    val sleepDeadline by SleepTimer.deadline.collectAsState()
    // Switching live pause on replaces the controller while this waits; stop whichever is current.
    val currentController by rememberUpdatedState(controller)
    LaunchedEffect(sleepDeadline) {
        val d = sleepDeadline ?: return@LaunchedEffect
        val wait = d - System.currentTimeMillis()
        if (wait > 0) delay(wait)
        SleepTimer.clear()
        currentController.stop()
        onBack()
    }

    // Apply the saved captions default once the stream's tracks arrive. Only auto-selects when
    // nothing is chosen yet, so it never overrides a track the user picked by hand.
    LaunchedEffect(tracks) {
        val hasText = tracks.groups.any { it.type == C.TRACK_TYPE_TEXT }
        val textChosen = tracks.groups.any { g ->
            g.type == C.TRACK_TYPE_TEXT && (0 until g.length).any { g.isTrackSelected(it) }
        }
        if (subtitlesDefault && hasText && !textChosen) controller.setSubtitlesEnabled(true)
    }

    LivePlayerContent(
        controller = controller,
        settings = settings,
        queue = remember { PlaybackQueue.items },
        variants = variants,
        currentId = currentId,
        previousId = previousId,
        recordingThis = activeRecordings.any { it.channelId == currentId },
        livePause = livePause,
        pausePromptDismissed = pausePromptDismissed,
        onPlayChannel = ::playChannelId,
        onTune = ::tuneTo,
        onToggleRecord = ::toggleRecord,
        onTurnOnLivePause = { settings.setLivePauseEnabled(true) },
        onDontAskAgain = { settings.setLivePausePromptDismissed(true) },
        onBack = onBack,
    )

    if (showBackgroundPrompt) {
        RecordingBackgroundDialog(
            onAllow = {
                showBackgroundPrompt = false
                context.requestIgnoreBatteryOptimizations()
            },
            onDismiss = { showBackgroundPrompt = false },
        )
    }
}

/**
 * The live picture and its control bar, channel list and pickers, with everything the remote drives.
 * [PlayerScreen] owns the stream, the channel being played and recording; this owns what the viewer
 * sees and presses.
 */
@OptIn(UnstableApi::class)
@Composable
fun LivePlayerContent(
    controller: PlayerController,
    settings: app.opentv.core.AppSettings,
    queue: List<PlaybackQueue.Item>,
    variants: List<Channel>,
    currentId: Long?,
    previousId: Long?,
    recordingThis: Boolean,
    livePause: Boolean,
    pausePromptDismissed: Boolean,
    onPlayChannel: (Long) -> Unit,
    onTune: (Channel) -> Unit,
    onToggleRecord: () -> Unit,
    onTurnOnLivePause: () -> Unit,
    onDontAskAgain: () -> Unit,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val state by controller.state.collectAsState()
    val tracks by controller.tracks.collectAsState()
    // Digits typed on the remote accumulate here, then jump to that channel number after a beat.
    var numberEntry by remember { mutableStateOf("") }
    val playWhenReady by controller.playWhenReady.collectAsState()
    // Nothing tuned yet isn't "paused": the player only means to play once a stream is set.
    val paused = state != PlayerController.State.Idle && !playWhenReady
    var resizeMode by remember { mutableIntStateOf(settings.playerResizeMode.value) }

    var controlsVisible by remember { mutableStateOf(true) }
    var panel by remember { mutableStateOf(Panel.NONE) }
    var channelListVisible by remember { mutableStateOf(false) }
    var interaction by remember { mutableIntStateOf(0) }

    // Picture-in-picture. While the player is on it is "eligible" to shrink to a floating window
    // (pressing Home does it, handled in MainActivity); [inPip] drives hiding all the chrome.
    val inPip by app.opentv.core.PipState.inPip.collectAsState()
    val pipSupported = remember {
        android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O &&
            context.packageManager.hasSystemFeature(
                android.content.pm.PackageManager.FEATURE_PICTURE_IN_PICTURE,
            )
    }
    LaunchedEffect(paused) { app.opentv.core.PipState.isPlaying = !paused }
    val barFocus = remember { FocusRequester() }
    val panelFocus = remember { FocusRequester() }
    val rootFocus = remember { FocusRequester() }
    val listFocus = remember { FocusRequester() }

    // While live TV is paused, how long until the forward buffer is full and the pause can't hold.
    var pauseLeftMillis by remember { mutableLongStateOf(0L) }
    var backToLive by remember { mutableStateOf(false) }
    var pausePrompt by remember { mutableStateOf(false) }
    // "Turn on and pause": the screen rebuilds the player with the DVR buffer and tunes the channel
    // again, and the pause waits for that new player to be playing.
    var pauseWhenBack by remember { mutableStateOf(false) }
    LaunchedEffect(state, livePause, pauseWhenBack) {
        if (pauseWhenBack && livePause && state is PlayerController.State.Playing) {
            pauseWhenBack = false
            controller.player.playWhenReady = false
        }
    }

    // The bar's Pause and the remote's play/pause. Pausing a stream with no buffer behind it only
    // drops the viewer behind live, so with live pause off it's offered rather than done.
    // After "Don't ask again", live TV without live pause simply can't be paused.
    val canPause = livePause || !pausePromptDismissed

    fun setPaused(pause: Boolean) {
        if (!livePause) {
            if (pause && !pausePromptDismissed) pausePrompt = true
            return
        }
        controller.player.playWhenReady = !pause
    }

    fun togglePause() = setPaused(!paused)
    LaunchedEffect(paused, livePause) {
        if (!paused || !livePause) return@LaunchedEffect
        pauseLeftMillis = PlayerController.DVR_MAX_BUFFER_MILLIS.toLong()
        while (pauseLeftMillis > 0) {
            delay(COUNTDOWN_TICK_MILLIS)
            pauseLeftMillis -= COUNTDOWN_TICK_MILLIS
        }
        // Past the buffer the provider stalls or drops the connection unpredictably, so rather than
        // leave the stream sitting there, pick it back up at the live edge.
        backToLive = true
        controller.player.seekToDefaultPosition()
        controller.player.playWhenReady = true
    }
    LaunchedEffect(backToLive) {
        if (!backToLive) return@LaunchedEffect
        delay(BACK_TO_LIVE_MESSAGE_MILLIS)
        backToLive = false
    }

    fun reveal() {
        controlsVisible = true
        interaction++
    }

    fun zapBy(delta: Int) {
        if (queue.isEmpty()) return
        val cur = queue.indexOfFirst { it.id == currentId }.let { if (it < 0) 0 else it }
        val next = (cur + delta).coerceIn(0, queue.size - 1)
        if (next != cur) onPlayChannel(queue[next].id)
    }

    // Auto-hide the bar after a few seconds — but never while paused or with a picker open.
    LaunchedEffect(controlsVisible, interaction, state, paused, panel) {
        if (controlsVisible &&
            !paused &&
            panel == Panel.NONE &&
            state is PlayerController.State.Playing
        ) {
            delay(CONTROLS_TIMEOUT_MILLIS)
            controlsVisible = false
        }
    }

    // Focus: a picker's first row when one is open, otherwise the bar, otherwise the full-screen
    // catcher (so the next remote press brings the bar back).
    LaunchedEffect(controlsVisible, panel) {
        if (controlsVisible) {
            delay(40)
            runCatching { if (panel != Panel.NONE) panelFocus.requestFocus() else barFocus.requestFocus() }
        } else {
            runCatching { rootFocus.requestFocus() }
        }
    }

    // Back steps back out one layer at a time — channel list, then picker, then the control bar —
    // and only leaves the player once nothing is on screen. From immersive it's a single press out,
    // so it never traps you, but it also no longer throws you all the way to the guide just because
    // you wanted to dismiss the bar.
    BackHandler {
        when {
            channelListVisible -> channelListVisible = false
            panel != Panel.NONE -> panel = Panel.NONE
            controlsVisible -> controlsVisible = false
            else -> {
                controller.stop()
                onBack()
            }
        }
    }

    // Number entry: once digits stop coming, jump to that channel number in the browsing list.
    LaunchedEffect(numberEntry) {
        if (numberEntry.isEmpty()) return@LaunchedEffect
        delay(NUMBER_ENTRY_TIMEOUT_MILLIS)
        val num = numberEntry.toIntOrNull()
        numberEntry = ""
        val target = num?.let { n -> queue.firstOrNull { it.number == n } }
        if (target != null) onPlayChannel(target.id)
    }

    // Focus the channel list when it opens; hand focus back to the video catcher when it closes.
    LaunchedEffect(channelListVisible) {
        if (channelListVisible) {
            delay(40)
            runCatching { listFocus.requestFocus() }
        } else if (!controlsVisible) {
            runCatching { rootFocus.requestFocus() }
        }
    }

    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black)
            .onPreviewKeyEvent { event ->
                // Media keys act whether or not the bar or a picker is up, and are always consumed.
                if (event.key in MEDIA_KEYS) {
                    // A held key repeats: rewind and fast-forward skip again on each repeat, but acting on
                    // a repeated play/pause would flick between paused and playing.
                    val firstPress = event.nativeKeyEvent.repeatCount == 0
                    if (event.type == KeyEventType.KeyDown) {
                        when (event.key) {
                            Key.MediaRewind -> if (livePause) controller.seekBackward()
                            Key.MediaFastForward -> if (livePause) controller.seekForward()
                            Key.MediaPlay -> if (firstPress) setPaused(false)
                            Key.MediaPause -> if (firstPress) setPaused(true)
                            else -> if (firstPress) togglePause()
                        }
                        if (canPause) reveal()
                    }
                    return@onPreviewKeyEvent true
                }
                if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                val digit = keyToDigit(event.key)
                when {
                    // Never swallow Back/Escape — they must reach the back handler.
                    event.key == Key.Back || event.key == Key.Escape -> false
                    // Typing a channel number jumps to it, TiviMate-style.
                    digit != null -> {
                        numberEntry = (numberEntry + digit).take(4)
                        reveal()
                        interaction++
                        true
                    }
                    // A picker or the channel list owns the whole d-pad while it's up.
                    channelListVisible || panel != Panel.NONE -> {
                        interaction++
                        false
                    }
                    // Channel up/down works whether or not the bar is showing — this remote has no
                    // CH+/CH- keys, so up/down IS the channel changer. Each zap flashes the bar as a
                    // channel banner, then it auto-hides.
                    event.key == Key.DirectionUp || event.key == Key.ChannelUp -> {
                        zapBy(-1)
                        reveal()
                        true
                    }
                    event.key == Key.DirectionDown || event.key == Key.ChannelDown -> {
                        zapBy(1)
                        reveal()
                        true
                    }
                    // With the bar up, left/right drive its buttons; let them through.
                    controlsVisible -> {
                        interaction++
                        false
                    }
                    // Immersive: left opens the channel list, right the quality picker.
                    event.key == Key.DirectionLeft -> {
                        if (queue.isNotEmpty()) channelListVisible = true
                        true
                    }
                    event.key == Key.DirectionRight -> {
                        if (variants.size > 1) {
                            reveal()
                            panel = Panel.QUALITY
                        }
                        true
                    }
                    else -> {
                        reveal()
                        true
                    }
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
            update = {
                it.player = controller.player
                it.resizeMode = resizeMode
            },
        )

        if (backToLive && !inPip) {
            Text(
                stringResource(R.string.player_back_to_live),
                color = Color.White,
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(32.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(Color.Black.copy(alpha = 0.82f))
                    .padding(horizontal = 24.dp, vertical = 12.dp),
            )
        }

        // The channel number as you type it, top-right, until it resolves.
        if (numberEntry.isNotEmpty() && !inPip) {
            Box(
                Modifier
                    .align(Alignment.TopEnd)
                    .padding(32.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(Color.Black.copy(alpha = 0.82f))
                    .padding(horizontal = 28.dp, vertical = 16.dp),
            ) {
                Text(numberEntry, color = Color.White, style = MaterialTheme.typography.displaySmall)
            }
        }

        when (val current = state) {
            is PlayerController.State.Buffering -> {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        CircularProgressIndicator()
                        Spacer(Modifier.height(16.dp))
                        Text(current.title, color = Color.White)
                    }
                }
            }

            is PlayerController.State.Error -> {
                Box(
                    Modifier
                        .fillMaxSize()
                        .background(Color.Black.copy(alpha = 0.85f)),
                    contentAlignment = Alignment.Center,
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier.padding(48.dp),
                    ) {
                        Text(current.title, style = MaterialTheme.typography.headlineSmall, color = Color.White)
                        Spacer(Modifier.height(12.dp))
                        Text(
                            current.message,
                            color = Color.White.copy(alpha = 0.85f),
                            textAlign = TextAlign.Center,
                        )
                        Spacer(Modifier.height(24.dp))
                        Button(onClick = { controller.retry() }) { Text(stringResource(R.string.common_try_again)) }
                    }
                }
            }

            else -> Unit
        }

        val channelTitle = when (val s = state) {
            is PlayerController.State.Buffering -> s.title
            is PlayerController.State.Playing -> s.title
            is PlayerController.State.Error -> s.title
            else -> ""
        }

        AnimatedVisibility(
            visible = controlsVisible && !inPip,
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
                // The open picker sits above the transport row.
                if (panel != Panel.NONE) {
                    OptionPanel(
                        panel = panel,
                        controller = controller,
                        settings = settings,
                        tracks = tracks,
                        variants = variants,
                        currentId = currentId,
                        resizeMode = resizeMode,
                        onResize = {
                            resizeMode = it
                            settings.setPlayerResizeMode(it)
                        },
                        onTune = onTune,
                        firstFocus = panelFocus,
                        onDone = {
                            panel = Panel.NONE
                            interaction++
                        },
                    )
                    Spacer(Modifier.height(16.dp))
                }

                if (paused && livePause) {
                    Text(
                        stringResource(R.string.player_paused_left, formatPlaybackTime(pauseLeftMillis)),
                        style = MaterialTheme.typography.titleMedium,
                        color = Color.White,
                    )
                    Spacer(Modifier.height(8.dp))
                }

                if (channelTitle.isNotEmpty()) {
                    Text(
                        channelTitle,
                        style = MaterialTheme.typography.headlineSmall,
                        color = Color.White,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Spacer(Modifier.height(12.dp))
                }

                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (controller.isSeekable) {
                        BarChip(Icons.Filled.FastRewind, stringResource(R.string.player_rewind), false) {
                            controller.seekBackward()
                            interaction++
                        }
                        Spacer(Modifier.width(10.dp))
                    }
                    if (canPause) {
                        BarChip(
                            icon = if (paused) Icons.Filled.PlayArrow else Icons.Filled.Pause,
                            label = if (paused) {
                                stringResource(
                                    R.string.common_play,
                                )
                            } else {
                                stringResource(R.string.player_pause)
                            },
                            selected = false,
                            focusRequester = barFocus,
                        ) {
                            togglePause()
                            interaction++
                        }
                    }
                    if (controller.isSeekable) {
                        Spacer(Modifier.width(10.dp))
                        BarChip(Icons.Filled.FastForward, stringResource(R.string.player_forward), false) {
                            controller.seekForward()
                            interaction++
                        }
                    }

                    Spacer(Modifier.width(20.dp))
                    BarChip(
                        Icons.Filled.Subtitles,
                        stringResource(R.string.player_subtitles),
                        panel == Panel.SUBTITLES,
                        // With Pause hidden, the bar opens on the next button along.
                        focusRequester = if (canPause) null else barFocus,
                    ) {
                        panel = if (panel == Panel.SUBTITLES) Panel.NONE else Panel.SUBTITLES
                        interaction++
                    }
                    Spacer(Modifier.width(10.dp))
                    BarChip(Icons.Filled.Audiotrack, stringResource(R.string.player_audio), panel == Panel.AUDIO) {
                        panel = if (panel == Panel.AUDIO) Panel.NONE else Panel.AUDIO
                        interaction++
                    }
                    if (variants.size > 1) {
                        Spacer(Modifier.width(10.dp))
                        BarChip(
                            Icons.Filled.HighQuality,
                            stringResource(R.string.player_quality),
                            panel == Panel.QUALITY,
                        ) {
                            panel = if (panel == Panel.QUALITY) Panel.NONE else Panel.QUALITY
                            interaction++
                        }
                    }
                    Spacer(Modifier.width(10.dp))
                    BarChip(Icons.Filled.AspectRatio, stringResource(R.string.player_aspect), panel == Panel.ASPECT) {
                        panel = if (panel == Panel.ASPECT) Panel.NONE else Panel.ASPECT
                        interaction++
                    }
                    Spacer(Modifier.width(10.dp))
                    BarChip(
                        icon = Icons.Filled.FiberManualRecord,
                        label = if (recordingThis) {
                            stringResource(
                                R.string.common_stop,
                            )
                        } else {
                            stringResource(R.string.player_record)
                        },
                        selected = recordingThis,
                        iconTint = Color(0xFFE53935),
                    ) {
                        onToggleRecord()
                        interaction++
                    }
                    if (pipSupported) {
                        Spacer(Modifier.width(10.dp))
                        BarChip(Icons.Filled.PictureInPictureAlt, stringResource(R.string.player_pop_out), false) {
                            (context.findActivity() as? app.opentv.MainActivity)?.enterPipNow()
                            interaction++
                        }
                    }
                }
            }
        }

        // Left-side transparent channel list — d-pad Left opens it, pick a channel to switch.
        AnimatedVisibility(
            visible = channelListVisible && !inPip,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier.align(Alignment.CenterStart),
        ) {
            val currentIndex = queue.indexOfFirst { it.id == currentId }.coerceAtLeast(0)
            val listState = rememberLazyListState()
            LaunchedEffect(Unit) { runCatching { listState.scrollToItem(currentIndex) } }
            Column(
                Modifier
                    .fillMaxHeight()
                    .width(380.dp)
                    .background(Color.Black.copy(alpha = 0.72f))
                    .padding(vertical = 16.dp),
            ) {
                Text(
                    stringResource(R.string.common_channels),
                    style = MaterialTheme.typography.titleMedium,
                    color = Color.White.copy(alpha = 0.7f),
                    modifier = Modifier.padding(horizontal = 16.dp),
                )
                Spacer(Modifier.height(8.dp))
                // A quick "jump back to the channel you just left" pin, TiviMate-style.
                val lastItem = previousId?.let { pid -> queue.firstOrNull { it.id == pid } }
                if (lastItem != null && lastItem.id != currentId) {
                    ChannelListRow(
                        item = lastItem,
                        playing = false,
                        focusRequester = null,
                        leadingLabel = stringResource(R.string.player_last),
                        onClick = {
                            onPlayChannel(lastItem.id)
                            channelListVisible = false
                        },
                    )
                    Spacer(Modifier.height(4.dp))
                }
                LazyColumn(state = listState) {
                    itemsIndexed(queue, key = { _, item -> item.id }) { index, item ->
                        ChannelListRow(
                            item = item,
                            playing = item.id == currentId,
                            focusRequester = if (index == currentIndex) listFocus else null,
                            onClick = {
                                onPlayChannel(item.id)
                                channelListVisible = false
                            },
                        )
                    }
                }
            }
        }
    }

    if (pausePrompt) {
        LivePausePrompt(
            onTurnOn = {
                pausePrompt = false
                pauseWhenBack = true
                onTurnOnLivePause()
            },
            onDontAskAgain = {
                pausePrompt = false
                onDontAskAgain()
            },
            onDismiss = { pausePrompt = false },
        )
    }
}

/** Offers to turn live pause on, explaining what it costs, when the viewer pauses without it. */
@Composable
private fun LivePausePrompt(onTurnOn: () -> Unit, onDontAskAgain: () -> Unit, onDismiss: () -> Unit) {
    val minutes = PlayerController.DVR_MAX_BUFFER_MILLIS / MILLIS_PER_MINUTE
    Dialog(onDismissRequest = onDismiss) {
        Column(
            Modifier
                .width(520.dp)
                .clip(RoundedCornerShape(16.dp))
                .background(MaterialTheme.colorScheme.surface)
                .padding(24.dp),
        ) {
            Text(
                stringResource(R.string.live_pause_prompt_title),
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(Modifier.height(12.dp))
            Text(
                pluralStringResource(R.plurals.live_pause_prompt_body, minutes, minutes),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(20.dp))
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TextButton(onClick = onDontAskAgain) {
                    Text(stringResource(R.string.live_pause_prompt_dont_ask))
                }
                Spacer(Modifier.width(8.dp))
                TextButton(onClick = onDismiss) {
                    Text(stringResource(R.string.live_pause_prompt_not_now))
                }
                Spacer(Modifier.width(8.dp))
                Button(onClick = onTurnOn, modifier = Modifier.startsFocused()) {
                    Text(stringResource(R.string.live_pause_prompt_turn_on))
                }
            }
        }
    }
}

@Composable
private fun ChannelListRow(
    item: PlaybackQueue.Item,
    playing: Boolean,
    focusRequester: FocusRequester?,
    onClick: () -> Unit,
    leadingLabel: String? = null,
) {
    var focused by remember { mutableStateOf(false) }
    val bg = when {
        focused -> MaterialTheme.colorScheme.primary
        playing -> MaterialTheme.colorScheme.primary.copy(alpha = 0.4f)
        else -> Color.Transparent
    }
    val fg = if (focused) MaterialTheme.colorScheme.onPrimary else Color.White
    Row(
        Modifier
            .fillMaxWidth()
            .then(if (focusRequester != null) Modifier.focusRequester(focusRequester) else Modifier)
            .onFocusChanged { focused = it.isFocused }
            .background(bg)
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // A "Last" tag, or the channel number if the provider gives one — a fixed-width slot so
        // the logos and names line up down the list.
        Text(
            leadingLabel ?: item.number?.toString().orEmpty(),
            style = MaterialTheme.typography.labelMedium,
            color = fg.copy(alpha = 0.7f),
            maxLines = 1,
            modifier = Modifier.width(40.dp),
        )
        AsyncImage(
            model = item.logoUrl,
            contentDescription = null,
            modifier = Modifier.size(32.dp).clip(RoundedCornerShape(4.dp)),
        )
        Spacer(Modifier.width(12.dp))
        Text(
            item.name,
            style = MaterialTheme.typography.titleMedium,
            color = fg,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

private enum class Panel { NONE, SUBTITLES, AUDIO, QUALITY, ASPECT }

@OptIn(UnstableApi::class)
@Composable
private fun OptionPanel(
    panel: Panel,
    controller: PlayerController,
    settings: app.opentv.core.AppSettings,
    tracks: Tracks,
    variants: List<Channel>,
    currentId: Long?,
    resizeMode: Int,
    onResize: (Int) -> Unit,
    onTune: (Channel) -> Unit,
    firstFocus: FocusRequester,
    onDone: () -> Unit,
) {
    val offLabel = stringResource(R.string.player_subtitles_off)
    val standardLabel = stringResource(R.string.player_quality_standard)
    val fitLabel = stringResource(R.string.player_aspect_fit)
    val fillLabel = stringResource(R.string.player_aspect_fill)
    val stretchLabel = stringResource(R.string.player_aspect_stretch)
    val options: List<Option> = when (panel) {
        Panel.SUBTITLES -> buildSubtitleOptions(controller, settings, tracks, offLabel, onDone)
        Panel.AUDIO -> buildAudioOptions(controller, tracks, onDone)
        Panel.QUALITY -> variants.map { v ->
            Option(v.qualityLabel.ifEmpty { standardLabel }, v.id == currentId) {
                onTune(v)
                onDone()
            }
        }
        Panel.ASPECT -> listOf(
            Option(fitLabel, resizeMode == AspectRatioFrameLayout.RESIZE_MODE_FIT) {
                onResize(AspectRatioFrameLayout.RESIZE_MODE_FIT)
                onDone()
            },
            Option(fillLabel, resizeMode == AspectRatioFrameLayout.RESIZE_MODE_ZOOM) {
                onResize(AspectRatioFrameLayout.RESIZE_MODE_ZOOM)
                onDone()
            },
            Option(stretchLabel, resizeMode == AspectRatioFrameLayout.RESIZE_MODE_FILL) {
                onResize(AspectRatioFrameLayout.RESIZE_MODE_FILL)
                onDone()
            },
        )
        Panel.NONE -> emptyList()
    }

    val title = when (panel) {
        Panel.SUBTITLES -> stringResource(R.string.player_subtitles)
        Panel.AUDIO -> stringResource(R.string.player_audio)
        Panel.QUALITY -> stringResource(R.string.player_quality)
        Panel.ASPECT -> stringResource(R.string.player_aspect_ratio_title)
        Panel.NONE -> ""
    }

    Column(
        Modifier
            .widthIn(max = 460.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(Color.Black.copy(alpha = 0.92f))
            .padding(16.dp),
    ) {
        Text(title, style = MaterialTheme.typography.titleMedium, color = Color.White.copy(alpha = 0.7f))
        Spacer(Modifier.height(8.dp))
        Column(
            Modifier.heightIn(max = 260.dp).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            if (options.isEmpty()) {
                Text(
                    stringResource(R.string.player_none_available),
                    color = Color.White.copy(alpha = 0.6f),
                    modifier = Modifier.padding(8.dp),
                )
            }
            options.forEachIndexed { index, option ->
                OptionRow(
                    label = option.label,
                    selected = option.selected,
                    onClick = option.onClick,
                    focusRequester = if (index == 0) firstFocus else null,
                )
            }
        }
    }
}

private data class Option(val label: String, val selected: Boolean, val onClick: () -> Unit)

@OptIn(UnstableApi::class)
private fun buildSubtitleOptions(
    controller: PlayerController,
    settings: app.opentv.core.AppSettings,
    tracks: Tracks,
    offLabel: String,
    onDone: () -> Unit,
): List<Option> {
    val textGroups = tracks.groups.filter { it.type == C.TRACK_TYPE_TEXT }
    val anySelected = textGroups.any { g -> (0 until g.length).any { g.isTrackSelected(it) } }
    val list = mutableListOf<Option>()
    list += Option(offLabel, !anySelected) {
        controller.disableText()
        settings.setSubtitlesEnabled(false)
        onDone()
    }
    textGroups.forEach { group ->
        for (i in 0 until group.length) {
            if (!group.isTrackSupported(i)) continue
            val format = group.getTrackFormat(i)
            list += Option(trackLabel(format.label, format.language, list.size), group.isTrackSelected(i)) {
                controller.selectTrack(group, i)
                settings.setSubtitlesEnabled(true)
                onDone()
            }
        }
    }
    return list
}

@OptIn(UnstableApi::class)
private fun buildAudioOptions(controller: PlayerController, tracks: Tracks, onDone: () -> Unit): List<Option> {
    val audioGroups = tracks.groups.filter { it.type == C.TRACK_TYPE_AUDIO }
    val list = mutableListOf<Option>()
    audioGroups.forEach { group ->
        for (i in 0 until group.length) {
            if (!group.isTrackSupported(i)) continue
            val format = group.getTrackFormat(i)
            list += Option(trackLabel(format.label, format.language, list.size), group.isTrackSelected(i)) {
                controller.selectTrack(group, i)
                onDone()
            }
        }
    }
    return list
}

private fun trackLabel(label: String?, language: String?, index: Int): String {
    if (!label.isNullOrBlank()) return label
    if (!language.isNullOrBlank() && language != "und") {
        return runCatching { Locale(language).displayLanguage.ifBlank { language } }.getOrDefault(language)
    }
    return "Track ${index + 1}"
}

@Composable
private fun OptionRow(label: String, selected: Boolean, onClick: () -> Unit, focusRequester: FocusRequester?) {
    var focused by remember { mutableStateOf(false) }
    val bg = if (focused) MaterialTheme.colorScheme.primary else Color.White.copy(alpha = 0.08f)
    val fg = if (focused) MaterialTheme.colorScheme.onPrimary else Color.White
    Row(
        modifier = Modifier
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
        if (selected) {
            Icon(Icons.Filled.Check, contentDescription = stringResource(R.string.common_selected), tint = fg)
        }
    }
}

@Composable
private fun BarChip(
    icon: ImageVector,
    label: String,
    selected: Boolean,
    focusRequester: FocusRequester? = null,
    iconTint: Color? = null,
    onClick: () -> Unit,
) {
    var focused by remember { mutableStateOf(false) }
    val container = when {
        focused -> MaterialTheme.colorScheme.primary
        selected -> MaterialTheme.colorScheme.primary.copy(alpha = 0.55f)
        else -> Color.White.copy(alpha = 0.14f)
    }
    val content = if (focused) MaterialTheme.colorScheme.onPrimary else Color.White

    Row(
        modifier = Modifier
            .then(if (focusRequester != null) Modifier.focusRequester(focusRequester) else Modifier)
            .onFocusChanged { focused = it.isFocused }
            .clip(RoundedCornerShape(12.dp))
            .background(container)
            .then(
                if (focused) {
                    Modifier.border(2.dp, MaterialTheme.colorScheme.primary, RoundedCornerShape(12.dp))
                } else {
                    Modifier
                },
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, contentDescription = label, tint = if (focused) content else (iconTint ?: content))
        Spacer(Modifier.width(6.dp))
        Text(label, style = MaterialTheme.typography.labelLarge, color = content)
    }
}

private const val CONTROLS_TIMEOUT_MILLIS = 5_000L
private const val NUMBER_ENTRY_TIMEOUT_MILLIS = 2_000L
private const val COUNTDOWN_TICK_MILLIS = 1_000L
private const val BACK_TO_LIVE_MESSAGE_MILLIS = 4_000L
private const val MILLIS_PER_MINUTE = 60_000
private val MEDIA_KEYS =
    setOf(Key.MediaPlayPause, Key.MediaPlay, Key.MediaPause, Key.MediaRewind, Key.MediaFastForward)

/** Maps a remote's number keys (top row and numeric keypad) to a digit, or null for other keys. */
private fun keyToDigit(key: Key): Char? = when (key) {
    Key.Zero, Key.NumPad0 -> '0'
    Key.One, Key.NumPad1 -> '1'
    Key.Two, Key.NumPad2 -> '2'
    Key.Three, Key.NumPad3 -> '3'
    Key.Four, Key.NumPad4 -> '4'
    Key.Five, Key.NumPad5 -> '5'
    Key.Six, Key.NumPad6 -> '6'
    Key.Seven, Key.NumPad7 -> '7'
    Key.Eight, Key.NumPad8 -> '8'
    Key.Nine, Key.NumPad9 -> '9'
    else -> null
}
