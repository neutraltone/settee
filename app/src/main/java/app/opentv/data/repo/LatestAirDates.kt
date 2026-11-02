/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv.data.repo

import app.opentv.data.db.SeriesDao
import app.opentv.data.model.Series
import app.opentv.data.model.Source
import app.opentv.data.remote.XtreamApi

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
    suspend fun refresh(source: Source) = lookUpDates(
        titles = seriesDao.mostRecentlyUpdated(source.id, count),
        tag = TAG,
        ask = { api.seriesInfo(source, it.seriesId) },
        date = { series, info -> info.latestAirDate ?: info.releaseDate ?: tmdbLastAirDate(series) },
        save = { found -> seriesDao.setLatestAirDates(found.map { (series, date) -> series.id to date }) },
    )

    companion object {
        private const val TAG = "LatestAirDates"

        /** How many of a source's most recently updated series are looked up after each sync. */
        const val CANDIDATES = 50
    }
}
