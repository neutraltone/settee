/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv.data.repo

import android.util.Log
import app.opentv.data.db.SeriesDao
import app.opentv.data.model.Series
import app.opentv.data.model.Source
import app.opentv.data.remote.XtreamApi
import kotlinx.coroutines.CancellationException

/**
 * Finds when a source's recently updated shows last had an episode out, for Home's Recently
 * Released Shows row.
 *
 * Only the [count] most recently updated series (`last_modified`) are looked up: an episode can't
 * have aired recently unless the provider also updated that series recently, and one
 * `get_series_info` per series across a whole catalogue would take hours. The episodes that call
 * returns are not stored; [CatalogRepository.ensureEpisodes] stays the one path that does.
 */
class LatestAirDates(
    private val seriesDao: SeriesDao,
    private val api: XtreamApi,
    private val count: Int = CANDIDATES,
    /** TMDB's `last_air_date` for a show, or null without a key or a match. */
    private val tmdbLastAirDate: suspend (Series) -> Long?,
) {
    suspend fun refresh(source: Source) {
        for (series in seriesDao.mostRecentlyUpdated(source.id, count)) {
            // One show's failure must not cost the others their dates; it is left out of the row.
            runCatching { latestAirDate(source, series) }
                .onFailure { if (it is CancellationException) throw it }
                .onFailure { Log.w(TAG, "Air date lookup failed for series ${series.seriesId}", it) }
                .getOrNull()
                ?.let { seriesDao.setLatestAirDate(series.id, it) }
        }
    }

    private suspend fun latestAirDate(source: Source, series: Series): Long? {
        val info = api.seriesInfo(source, series.seriesId) ?: return null
        return info.latestAirDate ?: info.releaseDate ?: tmdbLastAirDate(series)
    }

    companion object {
        private const val TAG = "LatestAirDates"

        /** How many of a source's most recently updated series are looked up after each sync. */
        const val CANDIDATES = 50
    }
}
