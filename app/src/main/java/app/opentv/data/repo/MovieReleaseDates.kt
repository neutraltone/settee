/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv.data.repo

import app.opentv.data.db.MovieDao
import app.opentv.data.model.Movie
import app.opentv.data.model.Source
import app.opentv.data.remote.XtreamApi

/**
 * Finds when a source's recently added movies came out, for Home's Recently Released Movies row:
 * most providers leave the date out of their movie list.
 *
 * Only the [count] most recently added movies are candidates, dated or not: one `get_vod_info` per
 * movie across a whole catalogue would take hours and spend the user's TMDB quota on titles nobody
 * looks at. A release date doesn't change, so one already found costs no calls. A movie still undated
 * is asked about again next sync. Only the date is written, because [CatalogRepository.movieDetail]
 * may be filling in the rest of the row at the same time.
 */
class MovieReleaseDates(
    private val movieDao: MovieDao,
    private val api: XtreamApi,
    private val count: Int = CANDIDATES,
    /** TMDB's release date for a movie, or null without a key or a match. */
    private val tmdbReleaseDate: suspend (Movie) -> Long?,
) {
    suspend fun refresh(source: Source) = lookUpDates(
        titles = movieDao.mostRecentlyAdded(source.id, count).filter { it.releaseDate == null },
        tag = TAG,
        ask = { api.movieInfo(source, it.streamId) },
        date = { movie, info -> info.releaseDate ?: tmdbReleaseDate(movie) },
        save = { found -> movieDao.setReleaseDates(found.map { (movie, date) -> movie.id to date }) },
    )

    companion object {
        private const val TAG = "MovieReleaseDates"

        /** How many of a source's most recently added movies are looked up after each sync. */
        const val CANDIDATES = 50
    }
}
