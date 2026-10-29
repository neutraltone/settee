/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv

import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.remember
import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavController
import app.opentv.ui.Tab

/*
 * Where Back goes (#67). The main screen's tab lives on its own back stack entry rather than in
 * remember: Navigation disposes the main screen while a player or detail page covers it, which
 * used to throw the tab away and rebuild the shell on Home.
 */

private const val SHELL_TAB = "shellTab"

/** The tab the main screen is showing. Survives every screen opened on top of it. */
@Composable
fun NavBackStackEntry.shellTab(): State<Tab> {
    val flow = remember(this) { savedStateHandle.getStateFlow(SHELL_TAB, Tab.HOME) }
    return flow.collectAsState()
}

/**
 * Switches the main screen to [tab], whether it's on screen or under something else. Does nothing
 * before there is one (first-run setup).
 */
fun NavController.selectShellTab(tab: Tab) {
    val main = runCatching { getBackStackEntry(Routes.MAIN) }.getOrNull() ?: return
    main.savedStateHandle[SHELL_TAB] = tab
}

/**
 * Opens the live player with the Live guide under it, for a channel that didn't start from the
 * guide (resume last channel on launch, a reminder), so Back lands somewhere you can pick the next.
 * A reminder can arrive on any screen; whatever was over the guide is closed.
 */
fun NavController.openLivePlayerOverGuide(channelId: Long) {
    selectShellTab(Tab.LIVE)
    navigate(Routes.player(channelId)) {
        popUpTo(Routes.MAIN)
    }
}

/**
 * Moves the live view onto a recording that has just started ([recordingRoute], a VOD player route)
 * and drops the live player off the back stack, so Back from the recording never reopens a live
 * stream: on a single-connection line that second stream would cut the recording and risk the
 * provider banning the account.
 */
fun NavController.switchToRecording(recordingRoute: String) {
    navigate(recordingRoute) {
        launchSingleTop = true
        popUpTo(Routes.PLAYER) { inclusive = true }
    }
}

/**
 * The detail page Back from the movie and episode player opens for [mediaKey]: the movie's page, or
 * an episode's series' page ([seriesOfEpisode] maps an episode's row id to its series' row id).
 * Null for what has no page — recordings, catch-up — and an episode whose series has gone.
 */
suspend fun titlePageFor(mediaKey: String, seriesOfEpisode: suspend (episodeId: Long) -> Long?): String? {
    val (kind, id) = mediaKey.split(':', limit = 2).takeIf { it.size == 2 } ?: return null
    val localId = id.toLongOrNull() ?: return null
    return when (kind) {
        "movie" -> Routes.movieDetail(localId)
        "ep" -> seriesOfEpisode(localId)?.let(Routes::seriesDetail)
        else -> null
    }
}

/**
 * Leaves the movie and episode player for [titlePage] (see [titlePageFor]). When that page is
 * already under the player it goes back to it; otherwise the page takes the player's place, so Back
 * from the page goes to wherever the viewer started instead of into the player again. Without a
 * page it just goes back.
 */
fun NavController.leaveVodPlayer(titlePage: String?) {
    if (titlePage == null || previousBackStackEntry?.filledRoute() == titlePage) {
        popBackStack()
        return
    }
    val player = currentBackStackEntry?.destination?.id ?: return
    navigate(titlePage) {
        popUpTo(player) { inclusive = true }
    }
}

/** The entry's route with its arguments filled in, e.g. `movie/9` for `movie/{movieId}`. */
private fun NavBackStackEntry.filledRoute(): String? {
    val args = arguments
    return destination.route?.replace(Regex("\\{(\\w+)\\}")) { match ->
        args?.getString(match.groupValues[1]).orEmpty()
    }
}
