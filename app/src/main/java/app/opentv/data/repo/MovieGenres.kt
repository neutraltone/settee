/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv.data.repo

import app.opentv.data.db.MovieDao
import app.opentv.data.model.Movie
import app.opentv.data.model.Source
import app.opentv.data.remote.TmdbMeta
import app.opentv.data.remote.XtreamApi
import kotlinx.coroutines.delay

/**
 * Asks the provider, and TMDB when the viewer has set a key, about each film neither has answered
 * about yet, for For you's genre rows and people's pages: most providers leave the genre, cast and
 * director out of their film list, and file films by service ("NETFLIX MOVIES") rather than genre,
 * so without this only the films someone opened would have them.
 *
 * Everything `get_vod_info` answers fills what the film lacks ([Movie.filledFrom]). TMDB ([tmdb])
 * fills what's still missing and replaces the director, which from a provider is only a guess, and
 * adds the writers and studios, which only it has ([Movie.withTmdb]). A film nobody has a genre for
 * is saved with a blank one. Each is marked once it has answered, a match or not, so it isn't asked
 * again; one that couldn't be reached is asked again next time, the other not.
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
    private val tmdb: MovieTmdb = MovieTmdb.None,
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

    /** The next [count] films [select] picks that aren't in [tried], added to it. */
    private suspend fun next(tried: MutableSet<Long>, select: suspend (Int) -> List<Movie>): List<Movie> =
        select(count + tried.size).filterNot { it.id in tried }.take(count).also { tried += it.map(Movie::id) }

    private class Batch(val asked: Int, val answering: Boolean)

    private suspend fun batch(source: Source, tried: MutableSet<Long>): Batch {
        val withTmdb = tmdb.ready
        val titles = next(tried) { movieDao.newestNotLookedUp(source.id, withTmdb, it) }
        val answering = lookUpEach(
            titles = titles,
            tag = TAG,
            ask = { lookUp(source, it, withTmdb) },
            found = { _, found -> found },
            save = { found -> movieDao.fillDetails(found.map { (_, details) -> details }) },
        )
        return Batch(titles.size, answering)
    }

    /**
     * [movie] with what the provider and, [withTmdb], TMDB say about it, each asked only if it hasn't
     * answered before; null if neither answered. All of the provider's answer is kept, not just the
     * genre: it's the only way films nobody opens get the people a person's page lists them by.
     */
    private suspend fun lookUp(source: Source, movie: Movie, withTmdb: Boolean): Movie? {
        var found = movie
        var answered = false
        if (!movie.detailsLookedUp) {
            api.movieInfo(source, movie.streamId)?.let {
                found = found.filledFrom(it)
                answered = true
            }
        }
        if (withTmdb && !movie.tmdbLookedUp) {
            tmdb.lookUp(found).onSuccess {
                found = found.withTmdb(it)
                answered = true
            }
        }
        // Blank, not missing, once everyone there is to ask has said they have none.
        if (found.genre == null && found.detailsLookedUp && (!withTmdb || found.tmdbLookedUp)) {
            found = found.copy(genre = "")
        }
        return found.takeIf { answered }
    }

    companion object {
        private const val TAG = "MovieGenres"

        /** How many films not looked up each batch asks about. */
        const val CANDIDATES = 200

        /** Between batches: a minute's rest after each 200. */
        const val BATCH_PAUSE_MILLIS = 60_000L
    }
}

/** TMDB as the background pass asks it. */
interface MovieTmdb {
    /** Whether the viewer has set a key. */
    val ready: Boolean

    /** What TMDB has for [movie]: null for no match, or a failure when TMDB couldn't be reached. */
    suspend fun lookUp(movie: Movie): Result<TmdbMeta?>

    /** No key: the pass asks only the provider. */
    object None : MovieTmdb {
        override val ready = false

        override suspend fun lookUp(movie: Movie): Result<TmdbMeta?> = Result.success(null)
    }
}
