/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv.data.repo

import app.opentv.data.model.Series
import app.opentv.data.remote.TmdbMeta
import app.opentv.data.remote.XtreamApi

/**
 * This series with what TMDB said about it ([meta], null for no match) [at] that time. TMDB only
 * fills what's empty, the provider's values winning, as on a first look-up; TMDB has no director for TV.
 */
internal fun Series.withTmdb(meta: TmdbMeta?, at: Long): Series = if (meta == null) {
    copy(tmdbAnsweredAt = at)
} else {
    copy(
        posterUrl = posterUrl ?: meta.posterUrl,
        backdropUrl = backdropUrl ?: meta.backdropUrl,
        plot = plot ?: meta.overview,
        cast = cast ?: meta.cast,
        genre = genre ?: meta.genre,
        tmdbId = tmdbId ?: meta.tmdbId,
        rating = rating ?: meta.rating,
        year = year ?: meta.year,
        tmdbAnsweredAt = at,
    )
}

/** This series with its empty details filled from the provider's `get_series_info` [info]. */
internal fun Series.filledFrom(info: XtreamApi.SeriesInfo): Series = copy(
    backdropUrl = backdropUrl ?: info.backdropUrl,
    cast = cast ?: info.cast,
    genre = genre ?: info.genre,
    tmdbId = tmdbId ?: info.tmdbId,
    plot = plot ?: info.plot,
    rating = rating ?: info.rating,
    year = year ?: info.year,
)
