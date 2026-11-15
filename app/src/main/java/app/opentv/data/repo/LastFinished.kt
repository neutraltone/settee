/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv.data.repo

import app.opentv.data.db.EpisodeDao
import app.opentv.data.db.MovieDao
import app.opentv.data.db.PlaybackPositionDao
import app.opentv.data.db.SeriesDao
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map

/**
 * The film or show the active Profile most recently finished, for Home's Because you watched: the newest
 * film or episode it watched past 93% (or marked Watched), an episode standing for its show. Part of
 * [ViewerLibrary], which owns the watch state it reads.
 */
class LastFinished internal constructor(
    private val positions: PlaybackPositionDao,
    private val movies: MovieDao,
    private val episodes: EpisodeDao,
    private val series: SeriesDao,
    private val activeProfileId: StateFlow<Long>,
) {
    /**
     * One the provider no longer lists, or that isn't [wanted] (a kind Home hides), is passed over for the
     * next newest. Null when it has finished nothing; otherwise it changes only when the Profile finishes
     * something else.
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    operator fun invoke(wanted: (VodTitle) -> Boolean = { true }): Flow<VodTitle?> = activeProfileId
        .flatMapLatest { pid -> positions.observeNewestFirst(pid) }
        .map { newestFirst -> newestFirst.filter { statusOf(it) == WatchStatus.Watched }.map { it.mediaKey } }
        .distinctUntilChanged()
        .map { finished -> finished.firstNotNullOfOrNull { key -> titleOf(key)?.takeIf(wanted) } }
        .distinctUntilChanged()

    /** The film, or the show of the episode, [mediaKey] names; null when it's gone. */
    private suspend fun titleOf(mediaKey: String): VodTitle? = when (val key = MediaKey.parse(mediaKey)) {
        is MediaKey.Movie -> movies.byId(key.movieId)?.let(VodTitle::OfMovie)
        is MediaKey.Episode -> episodes.byId(key.episodeId)
            ?.let { series.bySeriesId(it.sourceId, it.seriesId) }
            ?.let(VodTitle::OfSeries)
        else -> null
    }
}
