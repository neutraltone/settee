/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv.data.repo

import androidx.room.withTransaction
import app.opentv.data.db.OpenTvDatabase
import app.opentv.data.model.Episode
import app.opentv.data.model.Movie
import app.opentv.data.model.Series
import app.opentv.data.model.SeriesRef
import app.opentv.data.model.StreamKind
import app.opentv.data.model.ref

/**
 * Deletes the films, series and episodes a provider no longer lists, first moving their resume points to
 * a Version of the same title that it still does (#351).
 *
 * ADR 0007 keeps a resume point on the Version it was made on while both exist, since copies may not line
 * up. Once that Version is gone, a point on another is worth more than none, even one landing slightly off
 * in a different cut. Only with no Version left do the points go with the row, so no position is ever left
 * on a deleted title. A Version deleted by the same refresh is never where a point goes.
 *
 * Which Version, for each Profile's point: one that Profile already has a point on, keeping whichever of
 * the two is newer (newest-wins, as sync merges), and otherwise the one listed last, the newest row on a
 * tie. Favourites need nothing: they match by source name and provider id, so a title that comes back
 * keeps its star.
 */
class RetiredTitles internal constructor(private val db: OpenTvDatabase, private val versionIds: VersionIds) {
    private val positions = db.positions()
    private val retired = db.retiredTitles()

    /** Deletes [sourceId]'s films last listed before [stamp], once a whole list has been saved with it. */
    suspend fun movies(sourceId: Long, stamp: Long) {
        val isGone = { movie: Movie -> movie.sourceId == sourceId && movie.lastSeenMillis < stamp }
        // Grouped before the transaction, as grouping can wait a moment for its first pass, which
        // mustn't hold SQLite's writer.
        val remaining = retired.staleWatchedMovies(sourceId, stamp).associate { gone ->
            gone.id to versionsLeft(gone, isGone)
        }
        db.withTransaction {
            // Read again inside: a film played since has a point that mustn't be left behind.
            for (gone in retired.staleWatchedMovies(sourceId, stamp)) {
                val left = remaining[gone.id] ?: versionsLeft(gone, isGone)
                handOver(MediaKey.Movie(gone.id).stored, left.map { MediaKey.Movie(it.id).stored })
            }
            retired.deleteStaleMovies(sourceId, stamp)
        }
    }

    /**
     * Deletes [sourceId]'s series last listed before [stamp], and their episodes. Another Version's
     * episodes are stored only once its page has been opened, so those a point could go to are fetched
     * first with [fetchEpisodes]; one that fails leaves that Version with nothing to take it.
     */
    suspend fun series(sourceId: Long, stamp: Long, fetchEpisodes: suspend (Series) -> Unit) {
        val isGone = { series: Series -> series.sourceId == sourceId && series.lastSeenMillis < stamp }
        val shows = retired.watchedEpisodesOfStaleSeries(sourceId, stamp)
            .map { SeriesRef(it.sourceId, it.seriesId) }.distinct()
            .mapNotNull { db.series().bySeriesId(it.sourceId, it.seriesId) }
        val remaining = shows.associate { it.ref to versionsLeft(it, isGone) }
        for (version in remaining.values.flatten().distinctBy { it.id }) {
            if (db.episodes().forSeries(version.sourceId, version.seriesId).isEmpty()) fetchEpisodes(version)
        }
        db.withTransaction {
            val episodesOf = EpisodesOf()
            for (gone in retired.watchedEpisodesOfStaleSeries(sourceId, stamp)) {
                val left = remaining[SeriesRef(gone.sourceId, gone.seriesId)].orEmpty()
                handOver(gone, left.flatMap { episodesOf(it).sameAs(gone) })
            }
            retired.deleteEpisodesOfStaleSeries(sourceId, stamp)
            retired.deleteStaleSeries(sourceId, stamp)
        }
    }

    /**
     * Deletes [seriesId]'s stored episodes that a `get_series_info` reply listing [listed] (its episode
     * ids, never none) left out. A point goes to the episode of the same season and number the reply
     * did list, as when a provider uploads an episode again under a new id, or else to that episode in
     * another Version of the show.
     */
    suspend fun episodes(sourceId: Long, seriesId: String, listed: Set<String>) {
        val stored = db.episodes().forSeries(sourceId, seriesId)
        val (gone, kept) = stored.partition { it.episodeId !in listed }
        if (gone.isEmpty()) return
        val show = db.series().bySeriesId(sourceId, seriesId)
        val others = show?.let { versionsLeft(it) { false } }.orEmpty()
        db.withTransaction {
            val episodesOf = EpisodesOf()
            for (episode in gone) {
                // The newest row first: a provider's upload again gets a new id, higher than the old.
                val here = kept.sameAs(episode).sortedByDescending { it.id }
                handOver(episode, here + others.flatMap { episodesOf(it).sameAs(episode) })
            }
            gone.map { it.id }.chunked(DELETE_CHUNK).forEach { retired.deleteEpisodes(it) }
        }
    }

    /** [movie]'s Versions not [isGone], the one listed last first. */
    private suspend fun versionsLeft(movie: Movie, isGone: (Movie) -> Boolean): List<Movie> {
        val grouped = versionIds.of(StreamKind.MOVIE, movie.id)?.takeIf { it.size > 1 }
            ?.let { db.movies().byIds(it.asList()) }.orEmpty()
        // The TMDB id as well: a row this refresh added isn't grouped yet, and may be the only one left.
        val sameId = knownTmdbId(movie.tmdbId)?.let { db.movies().byTmdbId(it) }.orEmpty()
        return (grouped + sameId).distinctBy { it.id }
            .filter { it.id != movie.id && !isGone(it) }
            .sortedWith(compareByDescending<Movie> { it.lastSeenMillis }.thenByDescending { it.id })
    }

    /** [series]' Versions not [isGone], the one listed last first; see the [Movie] version. */
    private suspend fun versionsLeft(series: Series, isGone: (Series) -> Boolean): List<Series> {
        val grouped = versionIds.of(StreamKind.SERIES, series.id)?.takeIf { it.size > 1 }
            ?.let { db.series().byIds(it.asList()) }.orEmpty()
        val sameId = knownTmdbId(series.tmdbId)?.let { db.series().byTmdbId(it) }.orEmpty()
        return (grouped + sameId).distinctBy { it.id }
            .filter { it.id != series.id && !isGone(it) }
            .sortedWith(compareByDescending<Series> { it.lastSeenMillis }.thenByDescending { it.id })
    }

    private suspend fun handOver(gone: Episode, to: List<Episode>) =
        handOver(MediaKey.Episode(gone.id).stored, to.map { MediaKey.Episode(it.id).stored })

    /** Moves every Profile's point on [from] to the best of [to] (see the class), then deletes those on [from]. */
    private suspend fun handOver(from: String, to: List<String>) {
        for (point in retired.positionsOn(from)) {
            val theirs = to.mapNotNull { positions.get(point.profileId, it) }.maxByOrNull { it.updatedAtMillis }
            val target = theirs?.mediaKey ?: to.firstOrNull() ?: continue
            if (theirs == null || point.updatedAtMillis > theirs.updatedAtMillis) {
                positions.upsert(point.copy(mediaKey = target))
            }
        }
        retired.deletePositionsOn(from)
    }

    /** Each series' stored episodes, read once however many of its episodes are asked about. */
    private inner class EpisodesOf {
        private val read = HashMap<SeriesRef, List<Episode>>()

        suspend operator fun invoke(series: Series): List<Episode> =
            read.getOrPut(series.ref) { db.episodes().forSeries(series.sourceId, series.seriesId) }
    }

    private fun List<Episode>.sameAs(episode: Episode) =
        filter { it.season == episode.season && it.episodeNumber == episode.episodeNumber }

    private companion object {
        /** Ids bound in one delete, well under SQLite's 999 variables. */
        const val DELETE_CHUNK = 500
    }
}
