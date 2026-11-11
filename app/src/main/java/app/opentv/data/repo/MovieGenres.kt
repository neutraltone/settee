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
import kotlinx.coroutines.delay

/**
 * Asks the provider for the details of each film nobody has yet, for For you's genre rows and people's
 * pages: most providers leave the genre, cast and director out of their film list, and file films by
 * service ("NETFLIX MOVIES") rather than genre, so without this only the films someone opened would
 * have them.
 *
 * Everything `get_vod_info` answers fills what the film lacks ([Movie.filledFrom]). When neither the
 * list nor the provider has a genre and the viewer has set a TMDB key, [tmdbGenre] asks TMDB; a film
 * none of them has one for is saved with a blank genre. Either way the film is marked looked up, so it
 * isn't asked about again; one that couldn't be looked up is left for next time.
 *
 * [fillAll] works through every film not looked up, newest first, [count] at a time with
 * [pauseMillis] between batches, so a struggling panel never gets a flood of calls and a provider
 * that blocks busy accounts sees a steady trickle. Tied to the film list's 12-hourly fetch, 200 at a
 * time, a 30,000-film catalogue took months.
 */
class MovieGenres(
    private val movieDao: MovieDao,
    private val api: XtreamApi,
    private val count: Int = CANDIDATES,
    private val pauseMillis: Long = BATCH_PAUSE_MILLIS,
    private val tmdbGenre: suspend (Movie) -> String? = { null },
) {
    /** One batch of the newest films not looked up. False if the provider stopped answering. */
    suspend fun refresh(source: Source): Boolean = batch(source, mutableSetOf()).answering

    /**
     * Every film not looked up, a batch at a time, until none is left to ask about or the provider
     * stops answering. A film whose lookup failed isn't asked about again in this run, which would
     * otherwise go round it for ever.
     */
    suspend fun fillAll(source: Source) {
        val tried = mutableSetOf<Long>()
        while (true) {
            val done = batch(source, tried)
            if (!done.answering || done.asked < count) return
            delay(pauseMillis)
        }
    }

    /**
     * Asks TMDB about the films saved blank, which the provider had no genre for, for when the viewer
     * has added a key since they were looked up. The provider isn't asked again. True once every one
     * has been asked about; false if TMDB stopped answering, so the pass should run again.
     */
    suspend fun retryBlanks(source: Source): Boolean {
        val tried = mutableSetOf<Long>()
        while (true) {
            val titles = next(tried) { movieDao.newestWithBlankGenre(source.id, it) }
            val answering = lookUpEach(
                titles = titles,
                tag = TAG,
                // "" for no match, so that counts as an answer rather than TMDB not answering.
                ask = { tmdbGenre(it).orEmpty() },
                found = { _, genre -> genre.takeIf { it.isNotBlank() } },
                save = { found -> movieDao.fillBlankGenres(found.map { (movie, genre) -> movie.id to genre }) },
            )
            if (!answering) return false
            if (titles.size < count) return true
            delay(pauseMillis)
        }
    }

    /** The next [count] films [select] picks that aren't in [tried], added to it. */
    private suspend fun next(tried: MutableSet<Long>, select: suspend (Int) -> List<Movie>): List<Movie> =
        select(count + tried.size).filterNot { it.id in tried }.take(count).also { tried += it.map(Movie::id) }

    private class Batch(val asked: Int, val answering: Boolean)

    private suspend fun batch(source: Source, tried: MutableSet<Long>): Batch {
        val titles = next(tried) { movieDao.newestNotLookedUp(source.id, it) }
        val answering = lookUpEach(
            titles = titles,
            tag = TAG,
            ask = { api.movieInfo(source, it.streamId) },
            found = { movie, info ->
                // The rest of the answer is kept too: it's the only way films nobody opens get the
                // cast and director a person's page lists them by. TMDB only for a film with no genre
                // at all, so one the list or the provider gave one doesn't spend the viewer's quota.
                val filled = movie.filledFrom(info)
                if (filled.genre == null) filled.copy(genre = tmdbGenre(movie).orEmpty()) else filled
            },
            save = { found -> movieDao.fillDetails(found.map { (_, details) -> details }) },
        )
        return Batch(titles.size, answering)
    }

    companion object {
        private const val TAG = "MovieGenres"

        /** How many films not looked up each batch asks about. */
        const val CANDIDATES = 200

        /** Between batches: a minute's rest after each 200. */
        const val BATCH_PAUSE_MILLIS = 60_000L
    }
}
