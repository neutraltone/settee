/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv.ui.vod

import app.opentv.data.model.Episode
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
