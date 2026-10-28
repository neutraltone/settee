/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv.data.repo

import app.opentv.data.db.FavouriteDao
import app.opentv.data.model.Channel
import app.opentv.data.model.Favourite
import app.opentv.data.model.Movie
import app.opentv.data.model.Series
import app.opentv.data.model.StreamKind
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map

/**
 * The Favourites half of [ViewerLibrary], reached as `viewerLibrary.favourites`: each Profile's
 * Favourite channels, movies and series (ADR 0001). Only ViewerLibrary builds one.
 */
class Favourites internal constructor(private val dao: FavouriteDao, private val activeProfileId: StateFlow<Long>) {
    suspend fun set(channel: Channel, favourite: Boolean) =
        set(StreamKind.LIVE, channel.sourceId, channel.streamId, favourite)

    suspend fun set(movie: Movie, favourite: Boolean) = set(StreamKind.MOVIE, movie.sourceId, movie.streamId, favourite)

    suspend fun set(series: Series, favourite: Boolean) =
        set(StreamKind.SERIES, series.sourceId, series.seriesId, favourite)

    private suspend fun set(kind: StreamKind, sourceId: Long, itemId: String, favourite: Boolean) {
        val profileId = activeProfileId.value
        if (favourite) {
            dao.add(
                profileId,
                kind,
                sourceId,
                itemId,
            )
        } else {
            dao.remove(profileId, kind, sourceId, itemId)
        }
    }

    /** The active Profile's Favourite channels in guide order, leaving out channels hidden on this device. */
    @OptIn(ExperimentalCoroutinesApi::class)
    fun channels(): Flow<List<Channel>> = activeProfileId.flatMapLatest { dao.observeChannels(it) }

    /** Row ids of the active Profile's Favourite channels, hidden ones included: what lights each star. */
    @OptIn(ExperimentalCoroutinesApi::class)
    fun channelIds(): Flow<Set<Long>> = activeProfileId
        .flatMapLatest { dao.observeChannelIds(it) }
        .map { it.toSet() }

    /** The active Profile's Favourite movies, by name. */
    @OptIn(ExperimentalCoroutinesApi::class)
    fun movies(): Flow<List<Movie>> = activeProfileId.flatMapLatest { dao.observeMovies(it) }

    /** The active Profile's Favourite series, by name. */
    @OptIn(ExperimentalCoroutinesApi::class)
    fun series(): Flow<List<Series>> = activeProfileId.flatMapLatest { dao.observeSeries(it) }

    /** Every Profile's Favourites on this device — what sync sends to the other side. */
    suspend fun everyProfiles(): List<Favourite> = dao.all()

    /** Adds a Favourite synced from another device; sync never removes one. Returns whether it was new here. */
    suspend fun mergeSynced(favourite: Favourite): Boolean = dao.insert(favourite) != -1L
}
