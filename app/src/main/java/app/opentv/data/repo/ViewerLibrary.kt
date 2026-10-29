/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv.data.repo

import app.opentv.data.db.EpisodeDao
import app.opentv.data.db.FavouriteDao
import app.opentv.data.db.PlaybackPositionDao
import app.opentv.data.model.Episode
import app.opentv.data.model.PlaybackPosition
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map

/** Where the active Profile is with one movie or episode. */
sealed interface WatchStatus {
    data object Unwatched : WatchStatus

    /**
     * [progress] is how far through, 0..1; [remainingMillis] is how long is left, null when the
     * duration is unknown.
     */
    data class InProgress(val progress: Float, val remainingMillis: Long?) : WatchStatus

    data object Watched : WatchStatus
}

/** The active Profile's status for each episode, by local episode id; any episode not in it is Unwatched. */
data class EpisodeStatuses(private val byEpisodeId: Map<Long, WatchStatus> = emptyMap()) {
    operator fun get(episodeId: Long): WatchStatus = byEpisodeId[episodeId] ?: WatchStatus.Unwatched
}

/** One card in Continue Watching. */
sealed interface ContinueWatchingCard {
    /** A movie the Profile stopped part way through; [progress] is how far, 0..1. */
    data class Movie(val movieId: Long, val progress: Float) : ContinueWatchingCard

    /**
     * A series, picked back up at [episode]; [progress] is how far through it is, 0..1, or null for
     * an Up Next the Profile has never played.
     */
    data class Series(val episode: Episode, val progress: Float?) : ContinueWatchingCard
}

/** What a series page's main button does for the active Profile. */
sealed interface SeriesMainAction {
    val episode: Episode

    /** Pick [episode] back up where the Profile stopped. */
    data class Resume(override val episode: Episode) : SeriesMainAction

    /** Start [episode]: Up Next, or the first episode when there is no Up Next. */
    data class Play(override val episode: Episode) : SeriesMainAction
}

/**
 * Everything a Profile has watched and kept: the one owner of per-Profile watch state and
 * Favourites. Screens ask it for statuses, resume points and Favourites instead of reading those
 * tables themselves.
 */
class ViewerLibrary(
    private val positions: PlaybackPositionDao,
    private val episodes: EpisodeDao,
    favouriteDao: FavouriteDao,
    private val activeProfileId: StateFlow<Long>,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    /** The active Profile's Favourites, and every Profile's for sync. */
    val favourites = Favourites(favouriteDao, activeProfileId)

    private val cards = ContinueWatchingCards(positions, episodes)

    suspend fun recordProgress(mediaKey: String, positionMillis: Long, durationMillis: Long) {
        positions.upsert(
            PlaybackPosition(
                profileId = activeProfileId.value,
                mediaKey = mediaKey,
                positionMillis = positionMillis,
                durationMillis = durationMillis,
                updatedAtMillis = clock(),
            ),
        )
    }

    /**
     * Takes progress synced from another device if it is newer than this device's for the same
     * Profile and title (newest wins, so watching on either device resumes on the other).
     * Returns whether it was taken.
     */
    suspend fun mergeSynced(position: PlaybackPosition): Boolean {
        val existing = positions.get(position.profileId, position.mediaKey)
        if (existing != null && position.updatedAtMillis <= existing.updatedAtMillis) return false
        positions.upsert(position)
        return true
    }

    /** Every Profile's progress on this device — what sync sends to the other side. */
    suspend fun everyProfilesProgress(): List<PlaybackPosition> = positions.all()

    /** Where to start [mediaKey] for the active Profile: where they stopped, or 0 if Watched or never played. */
    suspend fun resumeFrom(mediaKey: String): Long {
        val pos = positions.get(activeProfileId.value, mediaKey) ?: return 0
        return if (statusOf(pos) is WatchStatus.InProgress) pos.positionMillis.coerceAtLeast(0) else 0
    }

    /**
     * Every movie [profileId] has started, Watched or not: genre-affinity recommendations count a
     * film abandoned half way as a taste signal too. Takes the Profile explicitly because the home
     * feed computes it for whichever Profile it is building for.
     */
    suspend fun startedMovieIds(profileId: Long): Set<Long> = positions.forProfile(profileId)
        .filter { it.mediaKey.startsWith(MOVIE_PREFIX) }
        .mapNotNull { it.mediaKey.removePrefix(MOVIE_PREFIX).toLongOrNull() }
        .toSet()

    /** The active Profile's Continue Watching for movies: each In Progress one, most recently played first. */
    @OptIn(ExperimentalCoroutinesApi::class)
    fun continueWatchingMovies(): Flow<List<ContinueWatchingCard>> = activeProfileId
        .flatMapLatest { pid -> cards.movies(pid) }
        .map { list -> list.map { it.card } }

    /**
     * The active Profile's Continue Watching for series: one card per series, at the episode it
     * played most recently if that is In Progress, or else its Up Next.
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    fun continueWatchingSeries(): Flow<List<ContinueWatchingCard>> = activeProfileId
        .flatMapLatest { pid -> cards.series(pid) }
        .map { list -> list.map { it.card } }

    /** Home's Continue Watching: movies and series together, most recently played first. */
    @OptIn(ExperimentalCoroutinesApi::class)
    fun continueWatching(): Flow<List<ContinueWatchingCard>> = activeProfileId
        .flatMapLatest { pid ->
            combine(cards.movies(pid), cards.series(pid)) { movies, series ->
                (movies + series).sortedByDescending { it.playedAtMillis }.take(IN_PROGRESS_LIMIT).map { it.card }
            }
        }

    /**
     * The Next Episode after [episode]: the one that follows it in season then episode order,
     * whatever the Profile last Watched (unlike Up Next). Null after a series' last episode, or
     * when the series' episodes aren't loaded.
     */
    suspend fun nextEpisode(episode: Episode): Episode? {
        val ordered = episodes.forSeries(episode.sourceId, episode.seriesId).sortedWith(EPISODE_ORDER)
        val at = ordered.indexOfFirst { it.id == episode.id }
        return if (at < 0) null else ordered.getOrNull(at + 1)
    }

    /** Every episode's status for the active Profile; re-emits only when a status actually changes. */
    @OptIn(ExperimentalCoroutinesApi::class)
    fun episodeStatuses(): Flow<EpisodeStatuses> = activeProfileId
        .flatMapLatest { pid -> positions.observeKind(pid, EPISODE_PREFIX) }
        .map { list ->
            EpisodeStatuses(
                list.mapNotNull { pos ->
                    pos.mediaKey.removePrefix(EPISODE_PREFIX).toLongOrNull()?.let { it to statusOf(pos) }
                }.toMap(),
            )
        }
        .distinctUntilChanged()

    /** The series page's main action over [episodes] (one series' episodes, any order); null when there are none. */
    @OptIn(ExperimentalCoroutinesApi::class)
    fun seriesMainAction(episodes: List<Episode>): Flow<SeriesMainAction?> {
        val ordered = episodes.sortedWith(EPISODE_ORDER)
        val indexByKey = ordered.withIndex().associate { (i, ep) -> "$EPISODE_PREFIX${ep.id}" to i }
        // Season 0 is where providers put specials: starting a series there would open on a
        // trailer or a behind-the-scenes, so the start is S1 E1 unless specials are all there is.
        val start = ordered.firstOrNull { it.season > 0 } ?: ordered.firstOrNull() ?: return flowOf(null)
        return activeProfileId
            .flatMapLatest { pid -> positions.observeKind(pid, EPISODE_PREFIX) }
            .map<List<PlaybackPosition>, SeriesMainAction?> { list ->
                val latest = list.filter { it.mediaKey in indexByKey }.maxByOrNull { it.updatedAtMillis }
                val at = latest?.let { indexByKey.getValue(it.mediaKey) }
                when {
                    at == null -> SeriesMainAction.Play(start)
                    statusOf(latest) is WatchStatus.InProgress -> SeriesMainAction.Resume(ordered[at])
                    // No Up Next after the last episode: offer the series from the start.
                    else -> SeriesMainAction.Play(ordered.getOrNull(at + 1) ?: start)
                }
            }
            .distinctUntilChanged()
    }
}

/** A card with when the Profile last played what it stands for, so movies and series can be interleaved. */
private data class Played(val card: ContinueWatchingCard, val playedAtMillis: Long)

/**
 * Builds each kind's Continue Watching cards for one Profile, each with when it was last played;
 * ViewerLibrary picks the Profile and decides how kinds are listed.
 */
private class ContinueWatchingCards(private val positions: PlaybackPositionDao, private val episodes: EpisodeDao) {
    fun movies(pid: Long): Flow<List<Played>> = positions.observeNewestFirst(pid).map { list ->
        list.asSequence()
            .filter { it.mediaKey.startsWith(MOVIE_PREFIX) }
            .mapNotNull { pos ->
                val id = pos.mediaKey.removePrefix(MOVIE_PREFIX).toLongOrNull() ?: return@mapNotNull null
                (statusOf(pos) as? WatchStatus.InProgress)?.let {
                    Played(ContinueWatchingCard.Movie(id, it.progress), pos.updatedAtMillis)
                }
            }
            .take(IN_PROGRESS_LIMIT)
            .toList()
    }

    // Played episodes are observed as well as positions so a newly added Up Next shows up.
    fun series(pid: Long): Flow<List<Played>> =
        combine(positions.observeNewestFirst(pid), episodes.observePlayed(pid)) { list, played ->
            val byKey = played.associateBy { "$EPISODE_PREFIX${it.id}" }
            val positionOf = list.associateBy { it.mediaKey }
            val latestPerSeries = list.mapNotNull { pos -> byKey[pos.mediaKey]?.let { it to pos } }
                .distinctBy { (ep, _) -> ep.sourceId to ep.seriesId }
            // Capped on cards, not series: a series with nothing left to watch takes no slot.
            buildList {
                for ((ep, pos) in latestPerSeries) {
                    if (size == IN_PROGRESS_LIMIT) break
                    seriesCard(ep, pos, positionOf)?.let { add(Played(it, pos.updatedAtMillis)) }
                }
            }
        }

    private suspend fun seriesCard(
        latest: Episode,
        pos: PlaybackPosition,
        positionOf: Map<String, PlaybackPosition>,
    ): ContinueWatchingCard? = when (val status = statusOf(pos)) {
        is WatchStatus.InProgress -> ContinueWatchingCard.Series(latest, status.progress)
        else -> {
            val ordered = episodes.forSeries(latest.sourceId, latest.seriesId).sortedWith(EPISODE_ORDER)
            ordered.getOrNull(ordered.indexOfFirst { it.id == latest.id } + 1)?.let { next ->
                // Up Next may have been started before an earlier episode was finished; the player
                // resumes it there, so the card shows that progress rather than none.
                val started = positionOf["$EPISODE_PREFIX${next.id}"]?.let(::statusOf) as? WatchStatus.InProgress
                ContinueWatchingCard.Series(next, started?.progress)
            }
        }
    }
}

private val EPISODE_ORDER = compareBy<Episode>({ it.season }, { it.episodeNumber })
private const val EPISODE_PREFIX = "ep:"
private const val MOVIE_PREFIX = "movie:"
private const val IN_PROGRESS_LIMIT = 30

/** Past this, the credits are rolling: the title counts as Watched and is not resumed. */
private const val WATCHED_FRACTION = 0.93

private fun statusOf(pos: PlaybackPosition): WatchStatus {
    // Synced positions can arrive without a duration; that says nothing about how far through.
    // Nor does a negative position, which only a confused peer would send.
    val position = pos.positionMillis.coerceAtLeast(0)
    val known = pos.durationMillis > 0
    val progress = if (known) position.toDouble() / pos.durationMillis else 0.0
    if (progress > WATCHED_FRACTION) return WatchStatus.Watched
    return WatchStatus.InProgress(progress.toFloat(), if (known) pos.durationMillis - position else null)
}
