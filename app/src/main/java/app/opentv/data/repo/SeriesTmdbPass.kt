/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv.data.repo

import app.opentv.data.db.SeriesDao
import app.opentv.data.model.Series
import app.opentv.data.model.Source
import app.opentv.data.remote.TmdbMeta

/**
 * Asks TMDB, with the viewer's TMDB Key, about each series once, and again once its answer is six
 * months old ([tmdbDue]), so series nobody has opened get their posters, synopses and cast too.
 * [MovieGenres]' counterpart, at the same pace: [count] at a time, newest first, [pauseMillis]
 * between batches. A separate pass because series have no provider look-up to share it with:
 * `get_series_info` is asked when a series is opened, for its episodes.
 *
 * TMDB only fills what's empty ([Series.withTmdb]), the provider's own values winning, and an
 * answer is dated whether it was a match or not; one TMDB couldn't give is asked again next time.
 */
class SeriesTmdbPass(
    private val seriesDao: SeriesDao,
    private val tmdb: SeriesTmdb,
    private val count: Int = MovieGenres.CANDIDATES,
    private val pauseMillis: Long = MovieGenres.BATCH_PAUSE_MILLIS,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    /**
     * Every series due a look-up, a batch at a time, until none is left or TMDB stops answering. Each
     * answer is merged into the row as it is when it's saved, since opening the series may have
     * filled it in meanwhile.
     */
    suspend fun fillAll(source: Source) {
        if (!tmdb.ready) return
        inBatches(
            count,
            pauseMillis,
            Series::id,
            select = { seriesDao.newestTmdbDue(source.id, clock() - TMDB_REFRESH_MILLIS, it) },
            lookUp = { titles ->
                val now = clock()
                lookUpEach(
                    titles = titles,
                    tag = TAG,
                    // A failure is null, which lookUpEach counts as TMDB not answering; no match is an Answer.
                    ask = { series -> tmdb.lookUp(series).fold(onSuccess = ::Answer, onFailure = { null }) },
                    found = { _, answer -> answer },
                    save = { found ->
                        seriesDao.inTransaction {
                            val filled = found.mapNotNull { (series, answer) ->
                                seriesDao.byId(series.id)?.withTmdb(answer.meta, now)
                            }
                            seriesDao.upsertAll(filled)
                        }
                    },
                )
            },
        )
    }

    /** TMDB's answer, [meta] null for no match, wrapped so [lookUpEach] can tell it from no answer. */
    private class Answer(val meta: TmdbMeta?)

    private companion object {
        const val TAG = "SeriesTmdbPass"
    }
}

/** TMDB as [SeriesTmdbPass] asks it. */
interface SeriesTmdb {
    /** Whether the viewer has saved a TMDB Key. */
    val ready: Boolean

    /** What TMDB has for [series]: null for no match, or a failure when TMDB couldn't be reached. */
    suspend fun lookUp(series: Series): Result<TmdbMeta?>
}
