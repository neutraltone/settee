/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.LiveTv
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.RadioButtonChecked
import androidx.compose.material.icons.filled.Tv
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import app.opentv.R
import app.opentv.core.AppSettings
import app.opentv.core.JourneyTimer
import app.opentv.core.ServiceLocator
import app.opentv.core.StatusBus
import app.opentv.core.findActivity
import app.opentv.data.model.Channel
import app.opentv.data.model.Movie
import app.opentv.data.model.Recording
import app.opentv.data.model.Series
import app.opentv.data.repo.MediaKey
import app.opentv.ui.channels.LiveScreen
import app.opentv.ui.design.AerialTv
import app.opentv.ui.design.AerialTvSize
import app.opentv.ui.design.ConfirmPanel
import app.opentv.ui.design.ProgressBar
import app.opentv.ui.design.SetteeColors
import app.opentv.ui.design.SetteeMotion
import app.opentv.ui.design.SetteeShapes
import app.opentv.ui.design.SetteeType
import app.opentv.ui.design.Text
import app.opentv.ui.recordings.RecordingsScreen
import app.opentv.ui.vod.MoviesScreen
import app.opentv.ui.vod.ProvidersCarry
import app.opentv.ui.vod.SeriesScreen
import kotlinx.coroutines.delay

/** The main screen's tabs, each an item in the rail ([MainShell]). */
enum class Tab(val labelRes: Int, val icon: ImageVector) {
    HOME(R.string.nav_home, Icons.Filled.Home),
    LIVE(R.string.nav_live_tv, Icons.Filled.LiveTv),
    MOVIES(R.string.nav_movies, Icons.Filled.Movie),
    SHOWS(R.string.nav_shows, Icons.Filled.Tv),

    // A ring round a dot, a recorder's button: the plain dot read as a bullet on the closed rail.
    RECORDINGS(R.string.nav_recordings, Icons.Filled.RadioButtonChecked),
}

/**
 * The tabs in rail order. Home is always first, so the app opens on it; a content type switched off
 * has its tab hidden (and its sync skipped in CatalogRepository). Home and Recordings are never
 * content types, so the shell is never blank even with all three types off.
 */
internal fun visibleTabs(liveEnabled: Boolean, moviesEnabled: Boolean, seriesEnabled: Boolean): List<Tab> = buildList {
    add(Tab.HOME)
    if (liveEnabled) add(Tab.LIVE)
    if (moviesEnabled) add(Tab.MOVIES)
    if (seriesEnabled) add(Tab.SHOWS)
    add(Tab.RECORDINGS)
}

@Composable
fun MainScreen(
    isTelevision: Boolean,
    providers: ProvidersCarry,
    isSyncing: Boolean,
    loadFailure: ProviderFailure? = null,
    onPlayChannel: (Channel) -> Unit,
    onOpenMovie: (Movie) -> Unit,
    onOpenSeries: (Series) -> Unit,
    onResume: (MediaKey) -> Unit,
    onAddSource: () -> Unit,
    onEditProvider: (sourceId: Long) -> Unit,
    onRefresh: () -> Unit,
    onOpenSearch: () -> Unit,
    onOpenSettings: () -> Unit,
    /** Settings › Providers on a provider's row, from Home's banner. */
    onOpenProviders: (sourceId: Long) -> Unit = {},
    onOpenProfiles: () -> Unit,
    onPlayRecording: (Recording) -> Unit,
    onPlayCatchup: (MediaKey.Catchup) -> Unit,
    activeProfileName: String,
    tab: Tab,
    onSelectTab: (Tab) -> Unit,
) {
    val context = LocalContext.current
    val settings = remember { AppSettings.get(context) }
    val journeys = remember { ServiceLocator.get(context).journeys }
    val liveEnabled by settings.liveEnabled.collectAsState()
    val moviesEnabled by settings.moviesEnabled.collectAsState()
    val seriesEnabled by settings.seriesEnabled.collectAsState()
    val visibleTabs = remember(liveEnabled, moviesEnabled, seriesEnabled) {
        visibleTabs(liveEnabled, moviesEnabled, seriesEnabled)
    }

    // If the selected tab gets hidden (its type toggled off while it's open), drop back to the
    // home tab so the content area never tries to show a tab that's no longer there.
    // Keyed on the tab too: the player can ask for Live on its way in, after Live was switched off.
    LaunchedEffect(visibleTabs, tab) {
        if (tab !in visibleTabs) onSelectTab(Tab.HOME)
    }

    var showExit by remember { mutableStateOf(false) }
    if (showExit) {
        ExitPrompt(
            onLeave = {
                showExit = false
                context.findActivity()?.finish()
            },
            onStay = { showExit = false },
        )
    }

    Column(Modifier.fillMaxSize()) {
        MainShell(
            tabs = visibleTabs,
            current = tab,
            onSelect = { selected ->
                journeys.tabSelected(selected)
                onSelectTab(selected)
            },
            onOpenSearch = onOpenSearch,
            onOpenSettings = onOpenSettings,
            onOpenProfiles = onOpenProfiles,
            activeProfileName = activeProfileName,
            onExit = { showExit = true },
            modifier = Modifier.weight(1f),
        ) { shown, takeFocus, onNothingToShow ->
            when (shown) {
                Tab.HOME -> HomeScreen(
                    onResume = onResume,
                    onOpenSeries = onOpenSeries,
                    onOpenMovie = onOpenMovie,
                    onPlayChannel = onPlayChannel,
                    onOpenLive = { onSelectTab(Tab.LIVE) },
                    takeFocus = takeFocus,
                    onNothingToShow = onNothingToShow,
                    onOpenProviders = onOpenProviders,
                )
                Tab.LIVE -> LiveScreen(
                    isTelevision = isTelevision,
                    hasSources = providers.hasProvider,
                    isSyncing = isSyncing,
                    loadFailure = loadFailure,
                    onPlayChannel = onPlayChannel,
                    onAddSource = onAddSource,
                    onRefresh = onRefresh,
                    onPlayCatchup = onPlayCatchup,
                    takeFocus = takeFocus,
                )
                Tab.MOVIES -> MoviesScreen(
                    onOpenMovie = onOpenMovie,
                    onResume = onResume,
                    providers = providers,
                    isSyncing = isSyncing,
                    onEditProvider = onEditProvider,
                    onAddProvider = onAddSource,
                    onOpenSettings = onOpenSettings,
                )
                Tab.SHOWS -> SeriesScreen(
                    onOpenSeries = onOpenSeries,
                    onResume = onResume,
                    providers = providers,
                    isSyncing = isSyncing,
                    onEditProvider = onEditProvider,
                    onAddProvider = onAddSource,
                    onOpenSettings = onOpenSettings,
                )
                Tab.RECORDINGS -> RecordingsScreen(onPlay = onPlayRecording)
            }
        }
    }
}

/**
 * Opening Movies or Shows is timed from its tab being picked to its first row drawn (ADR 0010); picking
 * any other tab first means the viewer left, and so does a cold start's Home.
 */
private fun JourneyTimer.tabSelected(tab: Tab) {
    cancel(JourneyTimer.Journey.COLD_START)
    cancel(JourneyTimer.Journey.OPEN_MOVIES)
    cancel(JourneyTimer.Journey.OPEN_SHOWS)
    when (tab) {
        Tab.MOVIES -> start(JourneyTimer.Journey.OPEN_MOVIES)
        Tab.SHOWS -> start(JourneyTimer.Journey.OPEN_SHOWS)
        Tab.HOME, Tab.LIVE, Tab.RECORDINGS -> Unit
    }
}

/** "Leave Settee?", asked before Back leaves the app from Home. The Highlight starts on Stay. */
@Composable
internal fun ExitPrompt(onLeave: () -> Unit, onStay: () -> Unit) {
    ConfirmPanel(
        title = stringResource(R.string.exit_title),
        body = stringResource(R.string.exit_body),
        confirm = stringResource(R.string.exit_confirm),
        keep = stringResource(R.string.exit_cancel),
        onConfirm = onLeave,
        onKeep = onStay,
    )
}

/**
 * What the app is doing in the background (a Catalogue Refresh's steps, device sync), in the
 * top-right corner inside the screen frame. Row titles start at the left, so that's free, and nothing
 * there takes the Highlight: at the bottom it covered the ring of a card in the last row. Drawn over
 * the screen rather than laid out under it: as a line along the foot it pushed every row, and the
 * Highlight with them, up when work started and back down when it stopped. It waits a second before
 * appearing, because most refreshes finish sooner and a line that blinks on and off reads as a
 * flicker, not news.
 */
@Composable
internal fun BackgroundWork(modifier: Modifier = Modifier) {
    val message by StatusBus.message.collectAsState()
    val progress by StatusBus.progress.collectAsState()
    val busy = message != null
    var visible by remember { mutableStateOf(false) }
    // Keyed on there being work, not on its words: a refresh's steps can each be shorter than the
    // wait, and restarting it at every step kept a refresh of several seconds from ever showing.
    LaunchedEffect(busy) {
        if (busy) delay(APPEAR_AFTER_MILLIS)
        visible = busy
    }
    // Words and bar both kept through the fade out, which runs after the bus has already cleared;
    // reading the bar live turned the guide's bar into the aerial TV as it faded.
    var lastMessage by remember { mutableStateOf("") }
    var lastProgress by remember { mutableStateOf<Float?>(null) }
    message?.let {
        lastMessage = it
        lastProgress = progress
    }
    AnimatedVisibility(
        visible = visible,
        modifier = modifier,
        enter = fadeIn(tween(FADE_MILLIS, easing = SetteeMotion.Arrive)),
        exit = fadeOut(tween(FADE_MILLIS)),
    ) {
        StatusLine(lastMessage, lastProgress)
    }
}

private const val APPEAR_AFTER_MILLIS = 1_000L
private const val FADE_MILLIS = 250

/**
 * The corner itself: quiet words on Surface, one line tall so it fits the band of the first row's
 * title and stays clear of that row's highlighted card. Work that knows how far through it is (only
 * the guide's download does) shows a short bar after the words, with no percentage to read; work that
 * doesn't shows the small aerial TV before them.
 */
@Composable
internal fun StatusLine(message: String, progress: Float?, modifier: Modifier = Modifier) {
    Row(
        modifier
            // Read out as one thing, the words and the aerial TV or bar together.
            .semantics(mergeDescendants = true) {}
            .widthIn(max = StatusLineMaxWidth)
            .background(SetteeColors.Surface, SetteeShapes.Card)
            .padding(horizontal = 14.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (progress == null) {
            AerialTv(stringResource(R.string.progress_still_working), size = AerialTvSize.Small)
            Spacer(Modifier.width(10.dp))
        }
        Text(message, SetteeType.Body, Modifier.weight(1f, fill = false), color = SetteeColors.QuietText, maxLines = 1)
        if (progress != null) {
            Spacer(Modifier.width(12.dp))
            ProgressBar(progress, Modifier.width(StatusBarWidth))
        }
    }
}

private val StatusLineMaxWidth = 360.dp
private val StatusBarWidth = 56.dp
