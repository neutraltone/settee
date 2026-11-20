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
 * Asks the provider, and TMDB in a build with a key, about each film neither has answered
 * about yet, for For you's genre rows and people's pages: most providers leave the genre, cast and
 * director out of their film list, and file films by service ("NETFLIX MOVIES") rather than genre,
 * so without this only the films someone opened would have them.
 *
 * Everything `get_vod_info` answers fills what the film lacks ([Movie.filledFrom]). TMDB ([tmdb])
 * fills what's still missing and replaces the director, which from a provider is only a guess, and
 * adds the writers and studios, which only it has ([Movie.withTmdb]). A film nobody has a genre for
 * is saved with a blank one. Each is marked once it has answered, a match or not, so it isn't asked
 * again, except TMDB once its answer is six months old ([tmdbDue]); one that couldn't be reached is
 * asked again next time.
 *
 * [fillAll] works through every film not looked up, newest first, at [pace], so a struggling panel
 * never gets a flood of calls and a provider that blocks busy accounts sees a steady trickle of at
 * most one `get_vod_info` a second. Tied to the film list's 12-hourly fetch, 200 at a time, a
 * 30,000-film catalogue took months; this way its provider look-ups take about nine hours.
 */
class MovieGenres(
    private val movieDao: MovieDao,
    private val api: XtreamApi,
    private val pace: LookupPace = LookupPace(),
    private val tmdb: MovieTmdb = MovieTmdb.None,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    /** One batch of the newest films not looked up. False if the provider stopped answering. */
    suspend fun refresh(source: Source): Boolean = lookUpBatch(source, dueFilms(source, pace.count))

    /** Every film not looked up, a batch at a time, until none is left or the provider stops answering. */
    suspend fun fillAll(source: Source) {
        val due: suspend (Int) -> List<Movie> = { dueFilms(source, it) }
        inBatches(pace.count, pace.pauseMillis, Movie::id, due) { lookUpBatch(source, it) }
    }

    /** The [limit] newest films the provider, or with a key TMDB, is due to be asked about. */
    private suspend fun dueFilms(source: Source, limit: Int): List<Movie> =
        movieDao.newestNotLookedUp(source.id, tmdb.ready, clock() - TMDB_REFRESH_MILLIS, limit)

    private suspend fun lookUpBatch(source: Source, titles: List<Movie>): Boolean {
        val withTmdb = tmdb.ready
        val now = clock()
        var providerAsked = false
        val askProvider: suspend (Movie) -> XtreamApi.VodInfo? = { movie ->
            if (providerAsked) delay(pace.providerGapMillis)
            providerAsked = true
            api.movieInfo(source, movie.streamId)
        }
        return lookUpEach(
            titles = titles,
            tag = TAG,
            ask = { lookUp(it, askProvider, withTmdb, now) },
            found = { _, found -> found },
            save = { found -> movieDao.fillDetails(found.map { (_, details) -> details }) },
        )
    }

    /**
     * [movie] with what the provider and, [withTmdb], TMDB say about it, each asked only if it hasn't
     * answered before; null if neither answered. All of the provider's answer is kept, not just the
     * genre: it's the only way films nobody opens get the people a person's page lists them by.
     */
    private suspend fun lookUp(
        movie: Movie,
        askProvider: suspend (Movie) -> XtreamApi.VodInfo?,
        withTmdb: Boolean,
        now: Long,
    ): Movie? {
        var found = movie
        var answered = false
        if (!movie.detailsLookedUp) {
            askProvider(movie)?.let {
                found = found.filledFrom(it)
                answered = true
            }
        }
        if (withTmdb && tmdbDue(movie.tmdbAnsweredAt, now)) {
            tmdb.lookUp(found).onSuccess {
                found = found.withTmdb(it, now)
                answered = true
            }
        }
        // Blank, not missing, once everyone there is to ask has said they have none.
        val everyoneAsked = found.detailsLookedUp && (!withTmdb || found.tmdbAnsweredAt != null)
        if (found.genre == null && everyoneAsked) {
            found = found.copy(genre = "")
        }
        return found.takeIf { answered }
    }

    companion object {
        private const val TAG = "MovieGenres"
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
