/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv.ui.vod

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import app.opentv.R
import app.opentv.data.model.Episode
import app.opentv.data.repo.AddonsSourceId
import app.opentv.data.repo.SeriesMainAction

/** One season of a series: its [number] (0 for Specials) and its episodes in order. */
data class Season(val number: Int, val episodes: List<Episode>)

/**
 * [episodes] (one series', any order) as its seasons, in order, with Specials (Season 0) after the
 * last. Providers file trailers and extras there, so leading with them would put the least wanted
 * pill first.
 */
fun seasonsOf(episodes: List<Episode>): List<Season> = episodes
    .groupBy { it.season }
    .map { (number, eps) -> Season(number, eps.sortedBy { it.episodeNumber }) }
    .sortedWith(compareBy({ it.number == 0 }, { it.number }))

/**
 * The season a series page opens on: that of the episode [mainAction] plays, or the first season
 * while there's no main button yet. Null until any episodes have arrived.
 */
fun openingSeason(seasons: List<Season>, mainAction: SeriesMainAction?): Int? =
    mainAction?.episode?.season ?: seasons.firstOrNull()?.number

/**
 * The numbers of the [seasons] listed only from a copy kept from an add-on ([addons]), when others come
 * from a Provider: the seasons a Provider's show lacks (#683), whose pills say so. None for a show only
 * add-ons have, where it would tell nothing apart.
 */
fun seasonsFromAddons(seasons: List<Season>, addons: AddonsSourceId): Set<Int> {
    val (fromAddons, fromProviders) = seasons.partition { season -> season.episodes.all { addons.keeps(it.sourceId) } }
    return if (fromProviders.isEmpty()) emptySet() else fromAddons.mapTo(HashSet()) { it.number }
}

/** A season's pill: "Season 2", or "Specials" for Season 0. */
@Composable
internal fun seasonLabel(number: Int): String =
    if (number == 0) stringResource(R.string.vod_specials) else stringResource(R.string.vod_season, number)
