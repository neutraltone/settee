/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv.data.repo

import app.opentv.data.db.FavouriteDao
import app.opentv.data.db.PlaybackPositionDao
import app.opentv.data.model.Channel
import app.opentv.data.model.Favourite
import app.opentv.data.model.Movie
import app.opentv.data.model.PlaybackPosition
import app.opentv.data.model.Series
import app.opentv.data.model.StreamKind
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map

/** Where the active Profile is with one movie or episode. */
sealed interface WatchStatus {
    data object Unwatched : WatchStatus

    /** [progress] is how far through, 0..1. */
    data class InProgress(val progress: Float) : WatchStatus

    data object Watched : WatchStatus
}

/** A movie or episode ("movie:<id>" / "ep:<id>") the active Profile has started but not Watched. */
data class InProgressTitle(val mediaKey: String, val progress: Float)

/** The active Profile's status for each episode, by local episode id; any episode not in it is Unwatched. */
data class EpisodeStatuses(private val byEpisodeId: Map<Long, WatchStatus> = emptyMap()) {
    operator fun get(episodeId: Long): WatchStatus = byEpisodeId[episodeId] ?: WatchStatus.Unwatched
}

/**
 * Everything a Profile has watched and kept: the one owner of per-Profile watch state and
 * Favourites. Screens ask it for statuses, resume points and Favourites instead of reading those
 * tables themselves.
 */
class ViewerLibrary(
    private val positions: PlaybackPositionDao,
    private val favourites: FavouriteDao,
    private val activeProfileId: StateFlow<Long>,
    private val clock: () -> Long = System::currentTimeMillis,
) {
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

    /** The active Profile's In Progress titles, most recently played first. */
    @OptIn(ExperimentalCoroutinesApi::class)
    fun inProgress(): Flow<List<InProgressTitle>> = activeProfileId
        .flatMapLatest { pid -> positions.observeNewestFirst(pid) }
        .map { list ->
            list.asSequence()
                .mapNotNull { pos ->
                    (statusOf(pos) as? WatchStatus.InProgress)?.let { InProgressTitle(pos.mediaKey, it.progress) }
                }
                .take(IN_PROGRESS_LIMIT)
                .toList()
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

    suspend fun setFavourite(channel: Channel, favourite: Boolean) =
        setFavourite(Favourite(activeProfileId.value, StreamKind.LIVE, channel.streamUrl), favourite)

    suspend fun setFavourite(movie: Movie, favourite: Boolean) =
        setFavourite(Favourite(activeProfileId.value, StreamKind.MOVIE, movie.streamUrl), favourite)

    suspend fun setFavourite(series: Series, favourite: Boolean) =
        setFavourite(Favourite(activeProfileId.value, StreamKind.SERIES, series.seriesId), favourite)

    private suspend fun setFavourite(item: Favourite, favourite: Boolean) {
        if (favourite) favourites.upsert(item) else favourites.delete(item)
    }

    /** The active Profile's Favourite channels in guide order, leaving out channels hidden on this device. */
    @OptIn(ExperimentalCoroutinesApi::class)
    fun favouriteChannels(): Flow<List<Channel>> = activeProfileId.flatMapLatest { favourites.observeChannels(it) }

    /** Stream URLs of the active Profile's Favourite channels, hidden ones included: what lights each star. */
    @OptIn(ExperimentalCoroutinesApi::class)
    fun favouriteChannelUrls(): Flow<Set<String>> = activeProfileId
        .flatMapLatest { favourites.observeIdentities(it, StreamKind.LIVE) }
        .map { it.toSet() }

    /** The active Profile's Favourite movies, by name. */
    @OptIn(ExperimentalCoroutinesApi::class)
    fun favouriteMovies(): Flow<List<Movie>> = activeProfileId.flatMapLatest { favourites.observeMovies(it) }

    /** The active Profile's Favourite series, by name. */
    @OptIn(ExperimentalCoroutinesApi::class)
    fun favouriteSeries(): Flow<List<Series>> = activeProfileId.flatMapLatest { favourites.observeSeries(it) }

    /**
     * The active Profile's Favourites of one [kind], by identity (stream URL, or series id for
     * series) — what sync sends. Until sync groups Favourites by Profile it carries only the
     * active Profile's.
     */
    suspend fun favouriteIdentities(kind: StreamKind): List<String> = favourites.identities(activeProfileId.value, kind)

    /** Adds a Favourite synced from another device to the active Profile; sync never removes one. */
    suspend fun addSyncedFavourite(kind: StreamKind, identity: String) =
        favourites.upsert(Favourite(activeProfileId.value, kind, identity))

    private fun statusOf(pos: PlaybackPosition): WatchStatus {
        // Synced positions can arrive without a duration; that says nothing about how far through.
        // Nor does a negative position, which only a confused peer would send.
        val progress =
            if (pos.durationMillis > 0) pos.positionMillis.coerceAtLeast(0).toDouble() / pos.durationMillis else 0.0
        return if (progress > WATCHED_FRACTION) WatchStatus.Watched else WatchStatus.InProgress(progress.toFloat())
    }

    private companion object {
        const val EPISODE_PREFIX = "ep:"
        const val MOVIE_PREFIX = "movie:"
        const val IN_PROGRESS_LIMIT = 30

        /** Past this, the credits are rolling: the title counts as Watched and is not resumed. */
        const val WATCHED_FRACTION = 0.93
    }
}
