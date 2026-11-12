/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import app.opentv.R
import app.opentv.data.repo.CoverStatus

/** How long is left, as "22 min left". */
@Composable
internal fun minutesLeftText(remainingMillis: Long): String {
    // Nearest minute, not up like the sleep timer: this is a rough "how much is left", not a countdown.
    // Never 0, which would read as finished.
    val minutes = ((remainingMillis + 30_000) / 60_000).toInt().coerceAtLeast(1)
    return pluralStringResource(R.plurals.watch_badge_minutes_left, minutes, minutes)
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
