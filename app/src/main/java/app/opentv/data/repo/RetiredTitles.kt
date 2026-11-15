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
import app.opentv.data.model.VersionRow
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
 *
 * The Versions are found before each transaction opens: grouping can wait a moment for its first pass,
 * which mustn't hold SQLite's writer while playback positions and the guide queue behind it.
 */
class RetiredTitles internal constructor(private val db: OpenTvDatabase, private val versionIds: VersionIds) {
    private val positions = db.positions()
    private val retired = db.retiredTitles()

    /** Deletes [sourceId]'s films last listed before [stamp], once a whole list has been saved with it. */
    suspend fun movies(sourceId: Long, stamp: Long) {
        val isGone = { movie: Movie -> movie.sourceId == sourceId && movie.lastSeenMillis < stamp }
        val remaining = films.versionsLeft(retired.staleWatchedMovies(sourceId, stamp), sourceId, isGone)
        db.withTransaction {
            // Read again inside: a film first played since has a point that mustn't be left behind, and
            // goes by its TMDB id alone, since grouping can't be waited for here.
            for (gone in retired.staleWatchedMovies(sourceId, stamp)) {
                val left = remaining[gone.id] ?: films.left(gone, emptyList(), isGone)
                handOver(MediaKey.Movie(gone.id).stored, left.map { MediaKey.Movie(it.id).stored })
            }
            retired.deleteStaleMovies(sourceId, stamp)
        }
    }

    /**
     * Deletes [sourceId]'s series last listed before [stamp], and their episodes. Another Version's
     * episodes are stored only once its page has been opened, so those a point could go to are fetched
     * first with [fetchEpisodes] (see [fetchMissing]).
     */
    suspend fun series(sourceId: Long, stamp: Long, fetchEpisodes: suspend (Series) -> Unit) {
        val isGone = { series: Series -> series.sourceId == sourceId && series.lastSeenMillis < stamp }
        val gone = retired.watchedEpisodesOfStaleSeries(sourceId, stamp)
            .map { SeriesRef(it.sourceId, it.seriesId) }.distinct()
            .mapNotNull { db.series().bySeriesId(it.sourceId, it.seriesId) }
        val remaining = shows.versionsLeft(gone, sourceId, isGone).mapKeys { (id, _) -> gone.first { it.id == id }.ref }
        fetchMissing(remaining.values.flatten(), sourceId, fetchEpisodes)
        db.withTransaction {
            val episodesOf = EpisodesOf()
            for (episode in retired.watchedEpisodesOfStaleSeries(sourceId, stamp)) {
                val ref = SeriesRef(episode.sourceId, episode.seriesId)
                val left = remaining[ref]
                    ?: db.series().bySeriesId(ref.sourceId, ref.seriesId)?.let { shows.left(it, emptyList(), isGone) }
                handOver(episode, left.orEmpty().flatMap { episodesOf(it).sameAs(episode) })
            }
            retired.deleteEpisodesOfStaleSeries(sourceId, stamp)
            retired.deleteStaleSeries(sourceId, stamp)
        }
    }

    /**
     * Deletes [seriesId]'s stored episodes that a `get_series_info` reply listing [listed] (its episode
     * ids, never none) left out. A point goes to the episode of the same season and number the reply
     * did list, as when a provider uploads an episode again under a new id, or else to that episode in
     * another Version of the show, whose episodes are fetched first as for [series].
     */
    suspend fun episodes(
        sourceId: Long,
        seriesId: String,
        listed: Set<String>,
        fetchEpisodes: suspend (Series) -> Unit,
    ) {
        val stored = db.episodes().forSeries(sourceId, seriesId)
        val (gone, kept) = stored.partition { it.episodeId !in listed }
        if (gone.isEmpty()) return
        // Other Versions matter only for a point with no episode of its number left in this series.
        val stranded = gone.any { episode ->
            kept.sameAs(episode).isEmpty() && retired.positionsOn(MediaKey.Episode(episode.id).stored).isNotEmpty()
        }
        val show = db.series().bySeriesId(sourceId, seriesId)?.takeIf { stranded }
        val others = show?.let { shows.versionsLeft(listOf(it), sourceId) { false }[it.id] }.orEmpty()
        fetchMissing(others, sourceId, fetchEpisodes)
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

    /**
     * Fetches the episodes of each of [versions] that has none stored, those of [sourceId] only: that
     * provider's refresh is under way, and asking another provider's panel now would overlap whatever
     * it's downloading, when a provider's work takes turns. One that fails, or is another provider's,
     * leaves that Version with nothing to take a point.
     */
    private suspend fun fetchMissing(versions: List<Series>, sourceId: Long, fetchEpisodes: suspend (Series) -> Unit) {
        for (version in versions.distinctBy { it.id }) {
            if (version.sourceId != sourceId) continue
            if (db.episodes().forSeries(version.sourceId, version.seriesId).isEmpty()) fetchEpisodes(version)
        }
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

    private val films = object : Shelf<Movie>(StreamKind.MOVIE) {
        override suspend fun byIds(ids: List<Long>) = db.movies().byIds(ids)
        override suspend fun byTmdbId(tmdbId: String) = db.movies().byTmdbId(tmdbId)
        override suspend fun rowsOf(sourceId: Long) = retired.movieVersionRows(sourceId)
        override fun idOf(title: Movie) = title.id
        override fun tmdbIdOf(title: Movie) = title.tmdbId
        override fun lastSeenOf(title: Movie) = title.lastSeenMillis
    }

    private val shows = object : Shelf<Series>(StreamKind.SERIES) {
        override suspend fun byIds(ids: List<Long>) = db.series().byIds(ids)
        override suspend fun byTmdbId(tmdbId: String) = db.series().byTmdbId(tmdbId)
        override suspend fun rowsOf(sourceId: Long) = retired.seriesVersionRows(sourceId)
        override fun idOf(title: Series) = title.id
        override fun tmdbIdOf(title: Series) = title.tmdbId
        override fun lastSeenOf(title: Series) = title.lastSeenMillis
    }

    /** Finding a film's or a show's Versions, the same way for both. */
    private abstract inner class Shelf<T>(private val kind: StreamKind) {
        abstract suspend fun byIds(ids: List<Long>): List<T>
        abstract suspend fun byTmdbId(tmdbId: String): List<T>
        abstract suspend fun rowsOf(sourceId: Long): List<VersionRow>
        abstract fun idOf(title: T): Long
        abstract fun tmdbIdOf(title: T): String?
        abstract fun lastSeenOf(title: T): Long

        /**
         * Each of [gone] (by id) with its Versions not [isGone], the one listed last first. As lists group
         * them ([VersionIds]), plus what the grouping hasn't seen yet: the rows this refresh added, which
         * may be all that's left. By TMDB id those are found at once; a row without one joins only by its
         * name, so [sourceId]'s rows are grouped again here, where a provider lists a title again.
         */
        suspend fun versionsLeft(gone: List<T>, sourceId: Long, isGone: (T) -> Boolean): Map<Long, List<T>> {
            if (gone.isEmpty()) return emptyMap()
            val bySource = if (gone.any { knownTmdbId(tmdbIdOf(it)) == null }) sourceGroups(sourceId) else emptyMap()
            return gone.associate { title ->
                val indexed = versionIds.of(kind, idOf(title))?.asList().orEmpty()
                val grouped = indexed + bySource[idOf(title)].orEmpty()
                idOf(title) to left(title, grouped, isGone)
            }
        }

        /** [title]'s Versions in [grouped] or sharing its TMDB id, not [isGone], the one listed last first. */
        suspend fun left(title: T, grouped: List<Long>, isGone: (T) -> Boolean): List<T> {
            val ids = grouped.filter { it != idOf(title) }.distinct()
            val inGroup = if (ids.isEmpty()) emptyList() else byIds(ids)
            val sameId = knownTmdbId(tmdbIdOf(title))?.let { byTmdbId(it) }.orEmpty()
            return (inGroup + sameId).distinctBy { idOf(it) }
                .filter { idOf(it) != idOf(title) && !isGone(it) }
                .sortedWith(compareByDescending<T> { lastSeenOf(it) }.thenByDescending { idOf(it) })
        }

        /** Each of [sourceId]'s rows in a title with others there, to the ids of that title's rows. */
        private suspend fun sourceGroups(sourceId: Long): Map<Long, List<Long>> =
            groupVersions(rowsOf(sourceId), VersionRow::tmdbId, VersionRow::name, VersionRow::year)
                .filter { it.size > 1 }
                .flatMap { title -> title.map { it.id }.let { ids -> ids.map { id -> id to ids } } }
                .toMap()
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
