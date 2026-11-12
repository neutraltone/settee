/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.NewReleases
import androidx.compose.material.icons.outlined.Timelapse
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import app.opentv.R
import app.opentv.data.repo.CoverStatus
import app.opentv.data.repo.WatchStatus
import app.opentv.ui.design.Icon
import app.opentv.ui.design.SetteeColors
import app.opentv.ui.design.SetteeShapes
import app.opentv.ui.design.SetteeType
import app.opentv.ui.design.Text

/**
 * A quiet label saying where the Profile is with a title, for drawing on its artwork. Deliberately
 * not a filled icon in the focus colour: on a TV that reads as a button to press.
 */
@Composable
fun WatchBadge(status: WatchStatus, modifier: Modifier = Modifier) {
    when (status) {
        WatchStatus.Watched -> Label(Icons.Outlined.CheckCircle, stringResource(R.string.watch_badge_watched), modifier)
        is WatchStatus.InProgress -> Label(Icons.Outlined.Timelapse, inProgressText(status.remainingMillis), modifier)
        WatchStatus.Unwatched -> Unit
    }
}

/** The same quiet label for a New Episode, on its Continue Watching card. */
@Composable
fun NewEpisodeBadge(modifier: Modifier = Modifier) {
    Label(Icons.Outlined.NewReleases, stringResource(R.string.watch_badge_new_episode), modifier)
}

@Composable
private fun inProgressText(remainingMillis: Long?): String =
    remainingMillis?.let { minutesLeftText(it) } ?: stringResource(R.string.watch_badge_in_progress)

/** How long is left, as "22 min left". */
@Composable
internal fun minutesLeftText(remainingMillis: Long): String {
    // Nearest minute, not up like the sleep timer: this is a rough "how much is left", not a countdown.
    // Never 0, which would read as finished.
    val minutes = ((remainingMillis + 30_000) / 60_000).toInt().coerceAtLeast(1)
    return pluralStringResource(R.plurals.watch_badge_minutes_left, minutes, minutes)
}

@Composable
private fun Label(icon: ImageVector, text: String, modifier: Modifier) {
    val shape = SetteeShapes.Cell
    Row(
        modifier
            .semantics(mergeDescendants = true) {}
            .background(Color.Black.copy(alpha = 0.6f), shape)
            .border(1.dp, SetteeColors.Text.copy(alpha = 0.5f), shape)
            .padding(horizontal = 6.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, contentDescription = null, tint = SetteeColors.Text, modifier = Modifier.size(16.dp))
        Spacer(Modifier.width(4.dp))
        Text(text, SetteeType.Body)
    }
}

/**
 * The quiet lines on a cover: `S4 E3` over `22 min left`, or one of `22 min left`, `S4 E4`,
 * `New episode` or `Watched`.
 */
@Composable
internal fun coverStatusText(status: CoverStatus?): List<String> = when (status) {
    null -> emptyList()
    is CoverStatus.InProgress -> {
        val episode = status.episode?.let { stringResource(R.string.home_cover_episode, it.season, it.episodeNumber) }
        val left = status.remainingMillis?.let { minutesLeftText(it) }
        listOfNotNull(episode, left).ifEmpty { listOf(stringResource(R.string.watch_badge_in_progress)) }
    }
    is CoverStatus.UpNext -> listOf(
        stringResource(R.string.home_cover_episode, status.episode.season, status.episode.episodeNumber),
    )
    CoverStatus.NewEpisode -> listOf(stringResource(R.string.watch_badge_new_episode))
    CoverStatus.Watched -> listOf(stringResource(R.string.watch_badge_watched))
}
