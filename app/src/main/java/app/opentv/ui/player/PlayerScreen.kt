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
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PictureInPictureAlt
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Subtitles
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.media3.common.C
import androidx.media3.common.Tracks
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import app.opentv.R
import app.opentv.core.ServiceLocator
import app.opentv.core.SleepTimer
import app.opentv.core.findActivity
import app.opentv.data.model.Channel
import app.opentv.data.model.shownName
import app.opentv.data.repo.LineupChannel
import app.opentv.data.repo.MediaKey
import app.opentv.data.repo.toRecord
import app.opentv.player.PlaybackQueue
import app.opentv.player.PlayerController
import app.opentv.player.SettledChannel
import app.opentv.ui.formatPlaybackTime
import app.opentv.ui.options.ItemOption
import app.opentv.ui.options.LeaveDirectly
import app.opentv.ui.options.LocalConfirmLive
import app.opentv.ui.options.LocalOpenOptions
import app.opentv.ui.options.LocalRecordingStarted
import app.opentv.ui.options.OptionsFor
import app.opentv.ui.options.OptionsSubject
import app.opentv.ui.options.optionsClickable
import app.opentv.ui.options.rememberRowHighlight
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
 * quality and aspect ratio. It hides after a few seconds and OK brings it back. Changing channel
 * shows a brief [ChannelBanner] instead, which has no buttons and never takes focus. Nothing is ever
 * left permanently painted over the picture.
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
    val livePause by settings.livePauseEnabled.collectAsState()
    val pausePromptDismissed by settings.livePausePromptDismissed.collectAsState()
    // Rebuilt only when live pause is switched on from the player: the DVR buffer is fixed when the
    // player is built. Changing channel never rebuilds it (see PlayerController).
    val controller = remember(livePause) {
        PlayerController(
            context,
            scope,
            graph.streamingHttpClient,
            resolve = graph.playbackSources::forWatching,
            PlayerController.Options(
                // Text starts off; the Profile's Subtitle Preference turns it on once tracks arrive.
                subtitlesEnabled = false,
                // Opt-in shallow DVR so the transport's pause/rewind actually holds on a live stream.
                dvr = livePause,
            ),
        )
    }
    DisposableEffect(controller) { onDispose { controller.release() } }
    val tracks by controller.tracks.collectAsState()
    // What's recording right now, so the Record button can show as armed for this channel.
    val activeRecordings by graph.recordingRepository.observeActive().collectAsState(initial = emptyList())
    val favouriteIds by graph.viewerLibrary.favourites.channelIds().collectAsState(initial = null)

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

    // The Channel playing: its Qualities are the same as wherever it was picked from.
    var channel by remember { mutableStateOf<LineupChannel?>(null) }
    var queue by remember { mutableStateOf<List<PlaybackQueue.Item>>(emptyList()) }
    LaunchedEffect(Unit) {
        // Settled before watching, so a channel outside the list zaps through every Channel instead.
        channelId?.let { graph.channelLineup.zapChannels(around = it) }
        // Watched for as long as the player is open, which keeps the list ready for each zap.
        graph.channelLineup.zapList.collect { channels ->
            queue = channels.map { channel ->
                val primary = channel.primary
                val qualityIds = channel.qualities.mapTo(HashSet()) { it.id }
                PlaybackQueue.Item(primary.id, primary.shownName, primary.logoUrl, primary.number, qualityIds)
            }
        }
    }
    var currentId by remember { mutableStateOf<Long?>(null) }
    // The channel we were on before this one — powers the "Last channel" recall in the list.
    var previousId by remember { mutableStateOf<Long?>(null) }
    val recordingStarted = LocalRecordingStarted.current

    DisposableEffect(Unit) {
        app.opentv.core.PipState.eligible = true
        onDispose {
            app.opentv.core.PipState.eligible = false
            app.opentv.core.PipState.isPlaying = false
        }
    }

    // Its waits go with [scope], so leaving the player settles on nothing.
    val settled = remember { SettledChannel(scope) { graph.viewerLibrary.live.record(it) } }
    // Started rather than resumed: picture-in-picture is still watching, behind another app isn't.
    val inFront = LocalLifecycleOwner.current.lifecycle.currentStateFlow.collectAsState().value
        .isAtLeast(Lifecycle.State.STARTED)
    val playerState by controller.state.collectAsState()
    LaunchedEffect(playerState, inFront) { settled.playing(inFront && playerState is PlayerController.State.Playing) }

    fun showPlaying(channel: Channel) {
        currentId = channel.id
        settings.lastChannelId = channel.id
        settled.tunedTo(channel)
    }

    /** [lower] are what it may step down to: none for a Quality the viewer picked from the list. */
    fun tuneTo(channel: Channel, lower: List<Channel> = emptyList()) {
        showPlaying(channel)
        val request = PlayerController.Request(
            MediaKey.Live(channel.id),
            label = channel.qualityLabel,
            lower = lower.map { PlayerController.Quality(MediaKey.Live(it.id), it.qualityLabel) },
        )
        controller.play(request, debounce = false)
    }

    /** Plays row [id] as one Quality of its Channel, so the switch and Record cover the Channel's. */
    suspend fun play(id: Long) {
        val playing = graph.channelLineup.channelOf(id)
            // Hidden or locked since it was picked: still play what was asked for, on its own.
            ?: graph.catalogRepository.channel(id)?.let { LineupChannel(it, listOf(it)) }
            ?: return
        channel = playing
        val quality = playing.qualities.first { it.id == id }
        tuneTo(quality, lower = playing.qualitiesBelow(quality))
    }

    fun playChannelId(id: Long) {
        // Remember where we came from so "Last channel" can bounce straight back. Quality switches
        // go through tuneTo directly, so they never count as a channel change here.
        currentId?.let { if (it != id) previousId = it }
        scope.launch { play(id) }
    }

    /** Zaps [delta] Channels on from the one playing, whichever of its Qualities that is. */
    fun zap(delta: Int, atEnd: () -> Unit) {
        val from = currentId ?: return
        scope.launch {
            val next = graph.channelLineup.zap(from, delta)
            if (next != null) playChannelId(next.primary.id) else atEnd()
        }
    }

    // Any Quality's: switching Quality mid-recording mustn't offer to record the Channel again.
    fun activeRecording() = activeRecordings.firstOrNull { recording ->
        recording.channelId == currentId || channel?.has(recording.channelId) == true
    }

    fun toggleRecord() {
        val active = activeRecording()
        if (active != null) {
            graph.recordingEngine.stop(active.id)
        } else {
            // Every Quality, not just the switchable ones: the one playing is always among them.
            val watching = channel ?: return
            val playing = watching.qualities.firstOrNull { it.id == currentId } ?: return
            scope.launch { graph.recordingEngine.startChannel(graph.channelLineup.toRecord(watching, playing)) }
            recordingStarted()
        }
    }

    // By the Channel's Qualities, as it's starred: the one playing may not be the one that was.
    // Until the ids first arrive, what the lineup said when the Channel was found.
    val favourite = channel?.let { playing ->
        favouriteIds?.let { ids -> playing.qualities.any { it.id in ids } } ?: playing.favourite
    } == true

    fun toggleFavourite() {
        val playing = channel ?: return
        scope.launch { graph.channelLineup.setFavourite(playing, !favourite) }
    }

    LaunchedEffect(channelId) {
        play(channelId ?: return@LaunchedEffect)
    }
    // Switching live pause on rebuilds the controller; pick the channel straight back up on it.
    LaunchedEffect(controller) {
        if (controller.state.value == PlayerController.State.Idle) {
            channel?.qualities?.firstOrNull { it.id == currentId }?.let { tuneTo(it) }
        }
    }

    // The controller steps down by itself, so a picked Quality's list and the last channel follow it.
    LaunchedEffect(controller) {
        controller.steppedDownTo.collect { key ->
            val id = (key as? MediaKey.Live)?.channelId
            channel?.qualities?.firstOrNull { it.id == id }?.let(::showPlaying)
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

    val subtitlePicks = rememberProfileSubtitles(controller, graph.profileSettings, scope)
    val openOptions = LocalOpenOptions.current

    LivePlayerContent(
        controller = controller,
        settings = settings,
        queue = queue,
        variants = channel?.switchable.orEmpty(),
        currentId = currentId,
        previousId = previousId,
        bannerChannel = channel?.let { BannerChannel(it.key, it.primary.number, it.primary.shownName) },
        programmeOnNow = { now ->
            channel?.let { programmeTitleOnNow(graph.channelLineup, graph.epgRepository, it, now) }
        },
        recordingThis = activeRecording() != null,
        livePause = livePause,
        pausePromptDismissed = pausePromptDismissed,
        onPlayChannel = ::playChannelId,
        onZap = ::zap,
        onTune = { tuneTo(it) },
        onToggleRecord = ::toggleRecord,
        favourite = favourite,
        onToggleFavourite = ::toggleFavourite,
        onTurnOnLivePause = { settings.setLivePauseEnabled(true) },
        onDontAskAgain = { settings.setLivePausePromptDismissed(true) },
        subtitlePicks = subtitlePicks,
        onBack = onBack,
        onChannelOptions = { item, onWatch ->
            scope.launch {
                // The list holds queue items, not Channels. As when playing, a channel hidden or
                // locked since the list was built still answers, on its own.
                val picked = graph.channelLineup.channelOf(item.id)
                    ?: graph.catalogRepository.channel(item.id)?.let { LineupChannel(it, listOf(it)) }
                    ?: return@launch
                openOptions?.invoke(OptionsSubject.OfChannel(picked, onWatch), LeaveDirectly)
            }
        },
    )
}

/** The Channel a banner names. [key] is what says it's a different Channel: see [LineupChannel.key]. */
data class BannerChannel(val key: String, val number: Int?, val name: String)

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
    bannerChannel: BannerChannel?,
    programmeOnNow: suspend (Long) -> String?,
    recordingThis: Boolean,
    livePause: Boolean,
    pausePromptDismissed: Boolean,
    onPlayChannel: (Long) -> Unit,
    onZap: (delta: Int, atEnd: () -> Unit) -> Unit,
    onTune: (Channel) -> Unit,
    onToggleRecord: () -> Unit,
    favourite: Boolean,
    onToggleFavourite: () -> Unit,
    onTurnOnLivePause: () -> Unit,
    onDontAskAgain: () -> Unit,
    subtitlePicks: SubtitlePicks,
    onBack: () -> Unit,
    onChannelOptions: (PlaybackQueue.Item, onWatch: () -> Unit) -> Unit = { _, _ -> },
) {
    val context = LocalContext.current
    val confirmLive = LocalConfirmLive.current
    val state by controller.state.collectAsState()
    val tracks by controller.tracks.collectAsState()
    // Digits typed on the remote accumulate here, then jump to that channel number after a beat.
    var numberEntry by remember { mutableStateOf("") }
    val playWhenReady by controller.playWhenReady.collectAsState()
    // Nothing tuned yet isn't "paused": the player only means to play once a stream is set.
    val paused = state != PlayerController.State.Idle && !playWhenReady
    var resizeMode by remember { mutableIntStateOf(settings.playerResizeMode.value) }

    var controlsVisible by remember { mutableStateOf(false) }
    var bannerVisible by remember { mutableStateOf(false) }
    var bannerShows by remember { mutableIntStateOf(0) }
    var openingPress by remember { mutableStateOf(false) }
    var panel by remember { mutableStateOf(PlayerPanel.NONE) }
    var channelListVisible by remember { mutableStateOf(false) }
    var optionsOpen by remember { mutableStateOf(false) }
    var interaction by remember { mutableIntStateOf(0) }

    // Picture-in-picture. While the player is on it is "eligible" to shrink to a floating window
    // (pressing Home does it, handled in MainActivity); [inPip] drives hiding all the chrome.
    val inPip by app.opentv.core.PipState.inPip.collectAsState()
    // Not just hidden: it would come back unasked when the player returns to full screen.
    LaunchedEffect(inPip) { if (inPip) optionsOpen = false }
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
        countDownLivePause(controller.livePauseMillis.toLong(), controller.bufferFull) { pauseLeftMillis = it }
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
        bannerVisible = false
        interaction++
    }

    /** Says which Channel is on: the banner, or the bar's title if the bar's already up. */
    fun announceChannel() {
        if (controlsVisible) {
            interaction++
        } else if (!channelListVisible) {
            bannerVisible = true
            bannerShows++
        }
    }

    /** Picking the Channel that's on doesn't change it, so nothing else would announce it. */
    fun playPicked(item: PlaybackQueue.Item) {
        onPlayChannel(item.id)
        if (currentId in item.qualityIds) announceChannel()
    }

    /** Select on a row of the channel list, and Watch in that channel's options. */
    fun pickFromList(item: PlaybackQueue.Item) {
        val pick = {
            channelListVisible = false
            playPicked(item)
        }
        // The Channel already on opens no new stream, so there's no recording to warn about.
        if (currentId in item.qualityIds) pick() else confirmLive(pick)
    }

    // The list's last channel can leave it (hidden from its options), with the highlight.
    LaunchedEffect(queue.isEmpty()) {
        if (queue.isEmpty()) channelListVisible = false
    }

    // By the Channel, not currentId: a Quality switch changes currentId and stays on the Channel.
    LaunchedEffect(bannerChannel?.key) {
        if (bannerChannel != null) announceChannel()
    }

    // Not the bar's auto-hide, which waits for Playing: nothing here needs the viewer to act on it, so
    // it shouldn't outstay a channel that's still buffering or has failed.
    LaunchedEffect(bannerShows) {
        delay(BANNER_TIMEOUT_MILLIS)
        bannerVisible = false
    }

    // Auto-hide the bar after a few seconds — but never while paused or with a picker open.
    LaunchedEffect(controlsVisible, interaction, state, paused, panel, optionsOpen) {
        val inUse = panel != PlayerPanel.NONE || optionsOpen
        val watching = !paused && state is PlayerController.State.Playing
        if (controlsVisible && watching && !inUse) {
            delay(CONTROLS_TIMEOUT_MILLIS)
            controlsVisible = false
        }
    }

    // Focus: a picker's first row when one is open, otherwise the bar, otherwise the full-screen
    // catcher (so OK can bring the bar back, and up and down can change channel).
    LaunchedEffect(controlsVisible, panel) {
        if (controlsVisible) {
            delay(40)
            runCatching { if (panel != PlayerPanel.NONE) panelFocus.requestFocus() else barFocus.requestFocus() }
        } else {
            runCatching { rootFocus.requestFocus() }
            // The player opens with nothing else to focus, and without focus the remote does nothing,
            // so ask again after the beat the bar waits, in case the screen was still opening.
            delay(40)
            if (!channelListVisible) runCatching { rootFocus.requestFocus() }
        }
    }

    // Back steps back out one layer at a time — channel list, then picker, then the control bar —
    // and only leaves the player once nothing is on screen. From immersive it's a single press out,
    // so it never traps you, but it also no longer throws you all the way to the guide just because
    // you wanted to dismiss the bar.
    BackHandler {
        when {
            channelListVisible -> channelListVisible = false
            panel != PlayerPanel.NONE -> panel = PlayerPanel.NONE
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
        if (target != null) playPicked(target)
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
                // The press that opens the bar isn't also a press on the button focus lands on: held,
                // its repeats and release would reach that button and pause the channel. A new press
                // means the release went astray.
                if (openingPress && event.key in OK_KEYS) {
                    val releasing = event.type == KeyEventType.KeyUp
                    val repeating = event.type == KeyEventType.KeyDown && event.nativeKeyEvent.repeatCount > 0
                    if (!repeating) openingPress = false
                    if (releasing || repeating) return@onPreviewKeyEvent true
                }
                if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                val digit = keyToDigit(event.key)
                when {
                    // Never swallow Back/Escape — they must reach the back handler.
                    event.key == Key.Back || event.key == Key.Escape -> false
                    // Typing a channel number jumps to it, TiviMate-style.
                    digit != null -> {
                        numberEntry = (numberEntry + digit).take(4)
                        interaction++
                        true
                    }
                    // A picker or the channel list owns the whole d-pad while it's up.
                    channelListVisible || panel != PlayerPanel.NONE -> {
                        interaction++
                        false
                    }
                    event.key == Key.Menu -> {
                        if (!inPip) optionsOpen = true
                        true
                    }
                    // Channel up/down works whether or not the bar is showing — this remote has no
                    // CH+/CH- keys, so up/down IS the channel changer. Only announced if the zap finds
                    // nothing, at either end of the list: otherwise the Channel changing announces it,
                    // and announcing now would show the old one until the new one is found.
                    event.key == Key.DirectionUp || event.key == Key.ChannelUp -> {
                        onZap(-1, ::announceChannel)
                        true
                    }
                    event.key == Key.DirectionDown || event.key == Key.ChannelDown -> {
                        onZap(1, ::announceChannel)
                        true
                    }
                    // With the bar up, left/right drive its buttons; let them through.
                    controlsVisible -> {
                        interaction++
                        false
                    }
                    // Immersive: left opens the channel list, right the quality picker.
                    event.key == Key.DirectionLeft -> {
                        if (queue.isNotEmpty()) {
                            channelListVisible = true
                            bannerVisible = false
                        }
                        true
                    }
                    event.key == Key.DirectionRight -> {
                        if (variants.size > 1) {
                            reveal()
                            panel = PlayerPanel.QUALITY
                        }
                        true
                    }
                    event.key in OK_KEYS -> {
                        reveal()
                        openingPress = true
                        true
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
            update = {
                it.player = controller.player
                it.resizeMode = resizeMode
            },
        )

        if (backToLive && !inPip) {
            PlayerNotice(stringResource(R.string.player_back_to_live), Modifier.align(Alignment.TopCenter))
        }
        val notice by controller.notice.collectAsState()
        notice?.takeUnless { inPip }?.let { PlayerNotice(it, Modifier.align(Alignment.TopCenter)) }

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
                        if (current.canRetry) {
                            Spacer(Modifier.height(24.dp))
                            Button(onClick = { controller.retry() }) {
                                Text(stringResource(R.string.common_try_again))
                            }
                        }
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
                if (panel != PlayerPanel.NONE) {
                    OptionPanel(
                        panel = panel,
                        controller = controller,
                        subtitlePicks = subtitlePicks,
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
                            panel = PlayerPanel.NONE
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

                LivePlayerControls(
                    paused = paused,
                    onBack = {
                        controller.stop()
                        onBack()
                    },
                    onPlayPause = {
                        togglePause()
                        interaction++
                    },
                    playPauseFocus = barFocus,
                    canPause = canPause,
                    seekable = controller.isSeekable,
                    onRewind = {
                        controller.seekBackward()
                        interaction++
                    },
                    onForward = {
                        controller.seekForward()
                        interaction++
                    },
                    panel = panel,
                    onPanel = {
                        panel = if (panel == it) PlayerPanel.NONE else it
                        interaction++
                    },
                    hasQuality = variants.size > 1,
                    recording = recordingThis,
                    onRecord = {
                        onToggleRecord()
                        interaction++
                    },
                    onOptions = { optionsOpen = true },
                    canPopOut = pipSupported,
                    onPopOut = {
                        (context.findActivity() as? app.opentv.MainActivity)?.enterPipNow()
                        interaction++
                    },
                )
            }
        }

        if (bannerChannel != null) {
            AnimatedVisibility(
                visible = bannerVisible && !inPip,
                enter = fadeIn(),
                exit = fadeOut(),
                modifier = Modifier.align(Alignment.BottomStart),
            ) {
                ChannelBanner(bannerChannel, programmeOnNow)
            }
        }

        // Left-side transparent channel list — d-pad Left opens it, pick a channel to switch.
        AnimatedVisibility(
            visible = channelListVisible && !inPip,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier.align(Alignment.CenterStart),
        ) {
            val currentIndex = queue.indexOfFirst { currentId in it.qualityIds }.coerceAtLeast(0)
            val listState = rememberLazyListState()
            val highlight = rememberRowHighlight(queue, listState) { it.id }
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
                val lastItem = previousId?.let { pid -> queue.firstOrNull { pid in it.qualityIds } }
                if (lastItem != null && currentId !in lastItem.qualityIds) {
                    ChannelListRow(
                        item = lastItem,
                        playing = false,
                        focusRequester = null,
                        leadingLabel = stringResource(R.string.player_last),
                        onClick = { pickFromList(lastItem) },
                        onOptions = { onChannelOptions(lastItem) { pickFromList(lastItem) } },
                    )
                    Spacer(Modifier.height(4.dp))
                }
                LazyColumn(state = listState) {
                    itemsIndexed(queue, key = { _, item -> item.id }) { index, item ->
                        ChannelListRow(
                            item = item,
                            playing = currentId in item.qualityIds,
                            focusRequester = if (index == currentIndex) listFocus else null,
                            onClick = { pickFromList(item) },
                            onOptions = { onChannelOptions(item) { pickFromList(item) } },
                            modifier = highlight.modifier(index, item.id),
                        )
                    }
                }
            }
        }
    }

    if (optionsOpen) {
        PlayerOptions(
            options = OptionsFor.LivePlayer(variants.size > 1, favourite, recordingThis).options(),
            onChoose = { option ->
                optionsOpen = false
                val picker = when (option) {
                    ItemOption.Subtitles -> PlayerPanel.SUBTITLES
                    ItemOption.Audio -> PlayerPanel.AUDIO
                    ItemOption.Quality -> PlayerPanel.QUALITY
                    ItemOption.AspectRatio -> PlayerPanel.ASPECT
                    else -> null
                }
                if (picker != null) {
                    reveal()
                    panel = picker
                }
                when (option) {
                    ItemOption.AddFavourite, ItemOption.RemoveFavourite -> onToggleFavourite()
                    ItemOption.RecordNow, ItemOption.StopRecording -> onToggleRecord()
                    else -> Unit
                }
            },
            onDismiss = { optionsOpen = false },
        )
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

@Composable
private fun ChannelListRow(
    item: PlaybackQueue.Item,
    playing: Boolean,
    focusRequester: FocusRequester?,
    onClick: () -> Unit,
    onOptions: () -> Unit,
    leadingLabel: String? = null,
    modifier: Modifier = Modifier,
) {
    var focused by remember { mutableStateOf(false) }
    val bg = when {
        focused -> MaterialTheme.colorScheme.primary
        playing -> MaterialTheme.colorScheme.primary.copy(alpha = 0.4f)
        else -> Color.Transparent
    }
    val fg = if (focused) MaterialTheme.colorScheme.onPrimary else Color.White
    Row(
        modifier
            .fillMaxWidth()
            .then(if (focusRequester != null) Modifier.focusRequester(focusRequester) else Modifier)
            .onFocusChanged { focused = it.isFocused }
            .background(bg)
            .optionsClickable(onClick, onOptions)
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

internal enum class PlayerPanel { NONE, SUBTITLES, AUDIO, QUALITY, ASPECT }

@OptIn(UnstableApi::class)
@Composable
private fun OptionPanel(
    panel: PlayerPanel,
    controller: PlayerController,
    subtitlePicks: SubtitlePicks,
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
        PlayerPanel.SUBTITLES -> buildSubtitleOptions(controller, tracks, offLabel, subtitlePicks, onDone)
        PlayerPanel.AUDIO -> buildAudioOptions(controller, tracks, onDone)
        PlayerPanel.QUALITY -> variants.map { v ->
            Option(v.qualityLabel.ifEmpty { standardLabel }, v.id == currentId) {
                onTune(v)
                onDone()
            }
        }
        PlayerPanel.ASPECT -> listOf(
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
        PlayerPanel.NONE -> emptyList()
    }

    val title = when (panel) {
        PlayerPanel.SUBTITLES -> stringResource(R.string.player_subtitles)
        PlayerPanel.AUDIO -> stringResource(R.string.player_audio)
        PlayerPanel.QUALITY -> stringResource(R.string.player_quality)
        PlayerPanel.ASPECT -> stringResource(R.string.player_aspect_ratio_title)
        PlayerPanel.NONE -> ""
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
    tracks: Tracks,
    offLabel: String,
    subtitlePicks: SubtitlePicks,
    onDone: () -> Unit,
): List<Option> {
    val textGroups = tracks.groups.filter { it.type == C.TRACK_TYPE_TEXT }
    val anySelected = textGroups.any { g -> (0 until g.length).any { g.isTrackSelected(it) } }
    val list = mutableListOf<Option>()
    list += Option(offLabel, !anySelected) {
        controller.disableText()
        subtitlePicks.onOff()
        onDone()
    }
    textGroups.forEach { group ->
        for (i in 0 until group.length) {
            if (!group.isTrackSupported(i)) continue
            val format = group.getTrackFormat(i)
            list += Option(trackLabel(format.label, format.language, list.size), group.isTrackSelected(i)) {
                controller.selectTrack(group, i)
                subtitlePicks.onPicked(format.language)
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

/**
 * The live player's control row: Back, then the transport (rewind and forward only on a stream
 * that can seek), the pickers, Record and, where the device has it, pop-out.
 */
@Composable
internal fun LivePlayerControls(
    paused: Boolean,
    onBack: () -> Unit,
    onPlayPause: () -> Unit,
    playPauseFocus: FocusRequester? = null,
    // False once the viewer chose "Don't ask again" with live pause off: there's nothing to pause.
    canPause: Boolean = true,
    seekable: Boolean = false,
    onRewind: () -> Unit = {},
    onForward: () -> Unit = {},
    panel: PlayerPanel = PlayerPanel.NONE,
    onPanel: (PlayerPanel) -> Unit = {},
    hasQuality: Boolean = false,
    recording: Boolean = false,
    onRecord: () -> Unit = {},
    onOptions: () -> Unit = {},
    canPopOut: Boolean = false,
    onPopOut: () -> Unit = {},
) {
    // Every button at once is wider than a 1080p TV: scrolling keeps the last ones reachable.
    Row(Modifier.horizontalScroll(rememberScrollState()), verticalAlignment = Alignment.CenterVertically) {
        PlayerBackButton(onBack)
        Spacer(Modifier.width(20.dp))
        if (seekable) {
            PlayerChip(Icons.Filled.FastRewind, stringResource(R.string.player_rewind), onClick = onRewind)
            Spacer(Modifier.width(10.dp))
        }
        if (canPause) {
            PlayerChip(
                icon = if (paused) Icons.Filled.PlayArrow else Icons.Filled.Pause,
                label = stringResource(if (paused) R.string.common_play else R.string.player_pause),
                focusRequester = playPauseFocus,
                onClick = onPlayPause,
            )
        }
        if (seekable) {
            Spacer(Modifier.width(10.dp))
            PlayerChip(Icons.Filled.FastForward, stringResource(R.string.player_forward), onClick = onForward)
        }

        Spacer(Modifier.width(20.dp))
        PlayerChip(Icons.Filled.MoreVert, stringResource(R.string.player_options), onClick = onOptions)
        Spacer(Modifier.width(10.dp))
        PlayerChip(
            Icons.Filled.Subtitles,
            stringResource(R.string.player_subtitles),
            panel == PlayerPanel.SUBTITLES,
            // With Pause hidden, the bar opens on the next button along.
            focusRequester = if (canPause) null else playPauseFocus,
        ) {
            onPanel(PlayerPanel.SUBTITLES)
        }
        Spacer(Modifier.width(10.dp))
        PlayerChip(Icons.Filled.Audiotrack, stringResource(R.string.player_audio), panel == PlayerPanel.AUDIO) {
            onPanel(PlayerPanel.AUDIO)
        }
        if (hasQuality) {
            Spacer(Modifier.width(10.dp))
            PlayerChip(
                Icons.Filled.HighQuality,
                stringResource(R.string.player_quality),
                panel == PlayerPanel.QUALITY,
            ) {
                onPanel(PlayerPanel.QUALITY)
            }
        }
        Spacer(Modifier.width(10.dp))
        PlayerChip(Icons.Filled.AspectRatio, stringResource(R.string.player_aspect), panel == PlayerPanel.ASPECT) {
            onPanel(PlayerPanel.ASPECT)
        }
        Spacer(Modifier.width(10.dp))
        PlayerChip(
            icon = Icons.Filled.FiberManualRecord,
            label = stringResource(if (recording) R.string.common_stop else R.string.player_record),
            selected = recording,
            iconTint = Color(0xFFE53935),
            onClick = onRecord,
        )
        if (canPopOut) {
            Spacer(Modifier.width(10.dp))
            PlayerChip(Icons.Filled.PictureInPictureAlt, stringResource(R.string.player_pop_out), onClick = onPopOut)
        }
    }
}

private const val CONTROLS_TIMEOUT_MILLIS = 5_000L
private const val BANNER_TIMEOUT_MILLIS = 4_000L
private const val NUMBER_ENTRY_TIMEOUT_MILLIS = 2_000L
private const val BACK_TO_LIVE_MESSAGE_MILLIS = 4_000L
internal val OK_KEYS = setOf(Key.DirectionCenter, Key.Enter, Key.NumPadEnter, Key.ButtonA)
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
