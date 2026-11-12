/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.FiberManualRecord
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.LiveTv
import androidx.compose.material.icons.filled.Movie
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
import androidx.compose.ui.unit.dp
import app.opentv.R
import app.opentv.core.AppSettings
import app.opentv.core.StatusBus
import app.opentv.core.ViewerError
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
import app.opentv.ui.design.SetteeType
import app.opentv.ui.design.Text
import app.opentv.ui.design.percentText
import app.opentv.ui.recordings.RecordingsScreen
import app.opentv.ui.vod.MoviesScreen
import app.opentv.ui.vod.SeriesScreen

/** The main screen's tabs, each an item in the rail ([MainShell]). */
enum class Tab(val labelRes: Int, val icon: ImageVector) {
    HOME(R.string.nav_home, Icons.Filled.Home),
    LIVE(R.string.nav_live_tv, Icons.Filled.LiveTv),
    MOVIES(R.string.nav_movies, Icons.Filled.Movie),
    SHOWS(R.string.nav_shows, Icons.Filled.Tv),
    RECORDINGS(R.string.nav_recordings, Icons.Filled.FiberManualRecord),
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
    hasSources: Boolean,
    isSyncing: Boolean,
    loadFailure: ViewerError? = null,
    onPlayChannel: (Channel) -> Unit,
    onOpenMovie: (Movie) -> Unit,
    onOpenSeries: (Series) -> Unit,
    onResume: (MediaKey) -> Unit,
    onAddSource: () -> Unit,
    onRefresh: () -> Unit,
    onOpenSearch: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenProfiles: () -> Unit,
    onPlayRecording: (Recording) -> Unit,
    onPlayCatchup: (MediaKey.Catchup) -> Unit,
    activeProfileName: String,
    tab: Tab,
    onSelectTab: (Tab) -> Unit,
) {
    val context = LocalContext.current
    val settings = remember { AppSettings.get(context) }
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
            onSelect = onSelectTab,
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
                )
                Tab.LIVE -> LiveScreen(
                    isTelevision = isTelevision,
                    hasSources = hasSources,
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
                    hasSources = hasSources,
                    isSyncing = isSyncing,
                )
                Tab.SHOWS -> SeriesScreen(
                    onOpenSeries = onOpenSeries,
                    onResume = onResume,
                    hasSources = hasSources,
                    isSyncing = isSyncing,
                )
                Tab.RECORDINGS -> RecordingsScreen(onPlay = onPlayRecording)
            }
        }
        StatusBar()
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
 * A slim line along the bottom that says what the app is doing in the background, such as loading
 * channels or building the guide, so a slow moment on a big provider reads as work in progress, not
 * a frozen screen. Invisible when there's nothing to report.
 */
@Composable
private fun StatusBar() {
    val message by StatusBus.message.collectAsState()
    val progress by StatusBus.progress.collectAsState()
    message?.let { StatusLine(it, progress) }
}

/**
 * The status line itself: quiet text on Surface. Work that knows how far through it is shows the
 * percentage and a bar ([progress] from 0 to 1); work that doesn't shows the small aerial TV.
 */
@Composable
internal fun StatusLine(message: String, progress: Float?, modifier: Modifier = Modifier) {
    Column(
        modifier
            .fillMaxWidth()
            .background(SetteeColors.Surface)
            .padding(horizontal = 16.dp, vertical = 6.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (progress == null) {
                AerialTv(stringResource(R.string.progress_still_working), size = AerialTvSize.Small)
            } else {
                Text(
                    percentText(progress),
                    SetteeType.Body,
                    color = SetteeColors.Accent,
                )
            }
            Spacer(Modifier.width(12.dp))
            Text(message, SetteeType.Body, color = SetteeColors.QuietText, maxLines = 1)
        }
        if (progress != null) ProgressBar(progress, Modifier.fillMaxWidth().padding(top = 4.dp))
    }
}

