/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv.ui.vod

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.res.stringResource
import app.opentv.R
import app.opentv.data.model.StreamKind
import app.opentv.data.repo.CAM_BADGE
import app.opentv.data.repo.TitleIndex

/**
 * Each cover's badge, from [TitleIndex], for whatever draws a cover of a film or show. Provided once
 * over the main screen rather than passed to every row: Home, Movies, Shows, Search and a person's page
 * all draw covers. Without it, a cover goes by its own name.
 */
@Immutable
class CoverBadges(private val movies: TitleIndex.Titles, private val series: TitleIndex.Titles) {
    fun of(kind: StreamKind, id: Long, name: String): String? = when (kind) {
        StreamKind.SERIES -> series.badgeFor(id, name)
        else -> movies.badgeFor(id, name)
    }

    companion object {
        val NONE = CoverBadges(TitleIndex.Titles.NONE, TitleIndex.Titles.NONE)
    }
}

internal val LocalCoverBadges = staticCompositionLocalOf { CoverBadges.NONE }

/** The badge on the cover of film or show [id], provider name [name]: see [CoverBadges]. */
@Composable
internal fun rememberCoverBadge(kind: StreamKind, id: Long, name: String): String? {
    val badges = LocalCoverBadges.current
    return badgeText(remember(badges, kind, id, name) { badges.of(kind, id, name) })
}

/** A badge as the cover writes it: a Quality as it is, and the cam mark in the viewer's language. */
@Composable
internal fun badgeText(badge: String?): String? =
    if (badge == CAM_BADGE) stringResource(R.string.vod_quality_cam) else badge

/** [index]'s titles as cover badges, again whenever they're regrouped; until then, covers go by name. */
@Composable
internal fun rememberCoverBadges(index: TitleIndex): CoverBadges {
    val movies by index.movies.collectAsState()
    val series by index.series.collectAsState()
    return remember(movies, series) {
        CoverBadges(movies ?: TitleIndex.Titles.NONE, series ?: TitleIndex.Titles.NONE)
    }
}
