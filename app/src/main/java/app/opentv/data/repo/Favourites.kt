/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv.data.repo

import androidx.room.withTransaction
import app.opentv.data.db.OpenTvDatabase
import app.opentv.data.model.Channel
import app.opentv.data.model.Favourite
import app.opentv.data.model.Movie
import app.opentv.data.model.Series
import app.opentv.data.model.StreamKind
import app.opentv.sync.SyncFavourite
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map

/**
 * The Favourites half of [ViewerLibrary], reached as `viewerLibrary.favourites`: each Profile's
 * Favourite channels, movies and series (ADR 0001). Only ViewerLibrary builds one.
 */
class Favourites internal constructor(
    private val db: OpenTvDatabase,
    private val activeProfileId: StateFlow<Long>,
    private val clock: () -> Long,
    private val versionIds: VersionIds,
) {
    private val dao = db.favourites()
    private val profiles = db.profiles()

    suspend fun set(channel: Channel, favourite: Boolean) = db.setFavourite(
        activeProfileId.value,
        FavouriteTitle(StreamKind.LIVE, channel.sourceId, channel.streamId),
        favourite,
        clock(),
    )

    /**
     * Stars the Version of a film the viewer is on. Unstarring clears every Version of it, since the title
     * stays a Favourite while any of them is one (ADR 0007).
     */
    suspend fun set(movie: Movie, favourite: Boolean) {
        val versions = if (favourite) listOf(movie) else db.versionsOf(movie, versionIds)
        val at = clock()
        versions.forEach {
            db.setFavourite(
                activeProfileId.value,
                FavouriteTitle(StreamKind.MOVIE, it.sourceId, it.streamId),
                favourite,
                at,
            )
        }
    }

    /** As [set] for a film: starring takes this Version, unstarring clears every Version. */
    suspend fun set(series: Series, favourite: Boolean) {
        val versions = if (favourite) listOf(series) else db.versionsOf(series, versionIds)
        val at = clock()
        versions.forEach {
            db.setFavourite(
                activeProfileId.value,
                FavouriteTitle(StreamKind.SERIES, it.sourceId, it.seriesId),
                favourite,
                at,
            )
        }
    }

    /**
     * Whether the active Profile has this channel or Version as a Favourite: [itemId] is the provider's
     * stream id ([kind] LIVE or MOVIE) or series id (SERIES) within the source [sourceId].
     */
    suspend fun contains(kind: StreamKind, sourceId: Long, itemId: String): Boolean =
        dao.contains(activeProfileId.value, kind, sourceId, itemId)

    /** Whether the active Profile has any Version of [title] as a Favourite (ADR 0007). */
    suspend fun isFavourite(title: VodTitle): Boolean = when (title) {
        is VodTitle.OfMovie -> db.versionsOf(title.movie, versionIds).any {
            contains(StreamKind.MOVIE, it.sourceId, it.streamId)
        }
        is VodTitle.OfSeries ->
            db.versionsOf(title.series, versionIds).any { contains(StreamKind.SERIES, it.sourceId, it.seriesId) }
    }

    /** The active Profile's Favourite channels in guide order, leaving out channels hidden on this device. */
    @OptIn(ExperimentalCoroutinesApi::class)
    fun channels(): Flow<List<Channel>> = activeProfileId.flatMapLatest { dao.observeChannels(it) }

    /** Row ids of the active Profile's Favourite channels, hidden ones included: what lights each star. */
    @OptIn(ExperimentalCoroutinesApi::class)
    fun channelIds(): Flow<Set<Long>> = activeProfileId
        .flatMapLatest { dao.observeChannelIds(it) }
        .map { it.toSet() }

    /**
     * The active Profile's Favourite movies and series together, the most recently starred first, so
     * starring a title again brings it to the front. A title starred in several Versions is there once,
     * as the Version starred last.
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    fun titles(): Flow<List<VodTitle>> = activeProfileId.flatMapLatest { profileId ->
        combine(dao.observeStarredMovies(profileId), dao.observeStarredSeries(profileId)) { movies, series ->
            (
                movies.map { it.starredAtMillis to VodTitle.OfMovie(it.movie) } +
                    series.map { it.starredAtMillis to VodTitle.OfSeries(it.series) }
                )
                .sortedByDescending { it.first }
                .map { it.second }
                .distinctBy { it.titleKey() }
        }
    }

    /** Every Profile's Favourites on this device, unstarred ones included, as they travel to the other side. */
    suspend fun export(): List<SyncFavourite> {
        val names = profiles.directory()
        return dao.all().mapNotNull { fav ->
            names.name(fav.profileId)?.let {
                SyncFavourite(it, fav.kind.name, fav.source, fav.itemId, fav.changedAtMillis, fav.removed)
            }
        }
    }

    /**
     * Merges another device's Favourites, newest change wins, labels going by [history]. Returns how
     * many it starred or unstarred here.
     */
    suspend fun import(items: List<SyncFavourite>, history: ProfileHistory = ProfileHistory.NONE): Int {
        val names = profiles.directory()
        val placed = items.mapNotNull { item ->
            val kind = runCatching { StreamKind.valueOf(item.kind) }.getOrNull() ?: return@mapNotNull null
            val profileId = names.id(history.currentName(item.profile, item.changedAtMillis)) ?: return@mapNotNull null
            Favourite(profileId, kind, item.source, item.id, item.changedAtMillis, item.removed)
        }
        return db.mergeForProfiles(placed, { it.profileId }) { fav ->
            val here = dao.find(fav.profileId, fav.kind, fav.source, fav.itemId)
            val newer = isNewerChange(fav.changedAtMillis, here?.changedAtMillis)
            if (newer) dao.upsert(fav)
            newer && (here?.removed ?: true) != fav.removed
        }
    }
}

/** The active Profile's Favourite movies, by name: [Favourites.titles] as the Movies tab lists them. */
fun Favourites.movies(): Flow<List<Movie>> =
    titles().map { titles -> titles.mapNotNull { (it as? VodTitle.OfMovie)?.movie }.sortedBy { it.name } }
        .distinctUntilChanged()

/** The active Profile's Favourite series, by name: [Favourites.titles] as the Shows tab lists them. */
fun Favourites.series(): Flow<List<Series>> =
    titles().map { titles -> titles.mapNotNull { (it as? VodTitle.OfSeries)?.series }.sortedBy { it.name } }
        .distinctUntilChanged()

/** A title as a screen knows it: the provider's stream or series id within the source's row. */
private class FavouriteTitle(val kind: StreamKind, val sourceId: Long, val itemId: String)

/** Stars a title, or marks it unstarred if it was starred. A title never starred gets no row. */
private suspend fun OpenTvDatabase.setFavourite(
    profileId: Long,
    title: FavouriteTitle,
    favourite: Boolean,
    changedAtMillis: Long,
) {
    val source = sources().byId(title.sourceId)?.name ?: return
    val dao = favourites()
    // One transaction, so a sync's newer change to this row can't land between the read and the write.
    withTransaction {
        if (favourite) {
            dao.upsert(Favourite(profileId, title.kind, source, title.itemId, changedAtMillis))
        } else {
            dao.find(profileId, title.kind, source, title.itemId)?.let {
                dao.upsert(it.copy(removed = true, changedAtMillis = changedAtMillis))
            }
        }
    }
}

/** What makes two of a list's titles one: their TMDB id, or for a title without one, the row itself. */
private fun VodTitle.titleKey(): Any = when (this) {
    is VodTitle.OfMovie -> knownTmdbId(movie.tmdbId)?.let { "movie:$it" } ?: movie
    is VodTitle.OfSeries -> knownTmdbId(series.tmdbId)?.let { "series:$it" } ?: series
}
