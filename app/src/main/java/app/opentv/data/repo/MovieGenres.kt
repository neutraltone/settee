/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv.data.repo

import app.opentv.data.db.MovieDao
import app.opentv.data.model.Source
import app.opentv.data.remote.XtreamApi

/**
 * Asks the provider for the genres of a source's films, for For you's genre rows: most providers
 * leave the genre out of their film list, and file films by service ("NETFLIX MOVIES") rather than
 * genre, so without this only the films someone opened would have one.
 *
 * Only the [count] newest films with no genre are asked about each sync, so a catalogue of 30,000
 * fills in over the weeks rather than holding one sync up for hours, newest first since they are
 * the ones most worth finding. Only the provider is asked, never TMDB: that would spend the user's
 * quota on titles nobody looks at. A film the provider has no genre for is saved with a blank one,
 * so the next sync moves on to older films instead of asking about it for ever; one the provider
 * couldn't answer for is asked about again next sync.
 */
class MovieGenres(private val movieDao: MovieDao, private val api: XtreamApi, private val count: Int = CANDIDATES) {
    suspend fun refresh(source: Source) = lookUpEach(
        titles = movieDao.newestWithoutGenre(source.id, count),
        tag = TAG,
        ask = { api.movieInfo(source, it.streamId) },
        found = { _, info -> info.genre.orEmpty() },
        save = { found -> movieDao.fillGenres(found.map { (movie, genre) -> movie.id to genre }) },
    )

    companion object {
        private const val TAG = "MovieGenres"

        /** How many of a source's newest films without a genre are asked about after each sync. */
        const val CANDIDATES = 200
    }
}
