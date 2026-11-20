/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv.ui

import app.opentv.data.model.Channel
import app.opentv.data.model.Movie
import app.opentv.data.model.Series
import app.opentv.data.repo.HomeArrangement
import app.opentv.data.repo.LineupChannel
import app.opentv.data.repo.LivePreview
import app.opentv.data.repo.MediaKey
import app.opentv.data.repo.VodTitle
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Each Profile's last finished Home, kept so a cold start can draw it at once while the fresh rows
 * load (#595). A file per Profile in the app's own storage rather than a table: it's a cache that
 * nothing queries, a Room table would need a migration whenever its shape changed, and sync, which
 * collects only from its stores, never sees it.
 *
 * Only what the cards draw and the ids to open them by are kept: never a stream URL, which can hold a
 * provider's login (ADR 0004). Titles read back carry an empty one, so anything that plays looks the
 * title up again first.
 */
class HomeSnapshots(
    private val dir: File,
    /** The database's schema version: a Home saved under another is never drawn, its ids may mean other rows. */
    private val schemaVersion: Int,
    private val clock: () -> Long,
) {
    private val json = Json { ignoreUnknownKeys = true }

    suspend fun save(profileId: Long, home: HomeState) = withContext(Dispatchers.IO) {
        dir.mkdirs()
        val saved = SavedHome(
            format = FORMAT,
            schema = schemaVersion,
            savedAt = clock(),
            arrangement = home.arrangement.encode(),
            live = home.live?.let(::SavedLive),
            favouriteChannels = home.favouriteChannels.map(::SavedChannel),
            continueWatching = home.continueWatching.map(::SavedResume),
            favouriteShows = home.favouriteShows.map(::SavedCover),
            favouriteMovies = home.favouriteMovies.map(::SavedCover),
            recentShows = home.recentShows.map(::SavedCover),
            recentMovies = home.recentMovies.map(::SavedCover),
        )
        // Written aside and moved into place, so a cold start never reads half a file.
        val next = File(dir, "$profileId.json.tmp")
        next.writeText(json.encodeToString(SavedHome.serializer(), saved))
        next.renameTo(file(profileId))
    }

    suspend fun read(profileId: Long): HomeState? = withContext(Dispatchers.IO) {
        val text = runCatching { file(profileId).readText() }.getOrNull() ?: return@withContext null
        val saved = runCatching { json.decodeFromString(SavedHome.serializer(), text) }.getOrNull()
            ?: return@withContext null
        // Older than a week, it's more likely to mislead than help: Home holds places as it always did.
        val stale = saved.format != FORMAT || saved.schema != schemaVersion || clock() - saved.savedAt > MAX_AGE_MILLIS
        if (stale) return@withContext null
        HomeState(
            continueWatching = saved.continueWatching.mapNotNull(SavedResume::toItem),
            favouriteShows = saved.favouriteShows.map(SavedCover::toCover),
            favouriteMovies = saved.favouriteMovies.map(SavedCover::toCover),
            favouriteChannels = saved.favouriteChannels.map(SavedChannel::toHomeChannel),
            recentShows = saved.recentShows.map(SavedCover::toCover),
            recentMovies = saved.recentMovies.map(SavedCover::toCover),
            arrangement = HomeArrangement.decode(saved.arrangement),
            live = saved.live?.toLive(),
            liveStill = saved.live != null,
        )
    }

    private fun file(profileId: Long) = File(dir, "$profileId.json")

    private companion object {
        /** Raised whenever what's saved changes meaning, so an old file is passed over rather than misread. */
        const val FORMAT = 1
        const val MAX_AGE_MILLIS = 7L * 24 * 60 * 60 * 1000
    }
}

@Serializable
private data class SavedHome(
    val format: Int,
    val schema: Int,
    val savedAt: Long,
    /** As the Profile arranged Home, so a saved Home is never drawn in the usual order and then moved. */
    val arrangement: String,
    val live: SavedLive?,
    val favouriteChannels: List<SavedChannel>,
    val continueWatching: List<SavedResume>,
    val favouriteShows: List<SavedCover>,
    val favouriteMovies: List<SavedCover>,
    val recentShows: List<SavedCover>,
    val recentMovies: List<SavedCover>,
)

@Serializable
private data class SavedResume(
    val key: String,
    val title: String,
    val posterUrl: String?,
    val progress: Float?,
    val season: Int?,
    val episode: Int?,
    val newEpisode: Boolean,
    val name: String,
    val titleId: Long?,
    val titleKey: String,
) {
    constructor(item: ResumeItem) : this(
        item.key.stored, item.title, item.posterUrl, item.progress, item.episode?.season, item.episode?.episode,
        item.newEpisode, item.name, item.titleId, item.titleKey,
    )

    fun toItem(): ResumeItem? {
        val parsed = MediaKey.parse(key) ?: return null
        val shown = if (season != null && episode != null) SeasonEpisode(season, episode) else null
        return ResumeItem(
            key = parsed, title = title, posterUrl = posterUrl, progress = progress, episode = shown,
            newEpisode = newEpisode, name = name, titleId = titleId, titleKey = titleKey,
        )
    }
}

@Serializable
private data class SavedCover(
    val series: Boolean,
    val id: Long,
    val sourceId: Long,
    val providerId: String,
    val name: String,
    val posterUrl: String?,
    val year: Int?,
) {
    constructor(cover: Cover) : this(
        series = cover.title is VodTitle.OfSeries,
        id = cover.title.id,
        sourceId = when (val title = cover.title) {
            is VodTitle.OfMovie -> title.movie.sourceId
            is VodTitle.OfSeries -> title.series.sourceId
        },
        providerId = when (val title = cover.title) {
            is VodTitle.OfMovie -> title.movie.streamId
            is VodTitle.OfSeries -> title.series.seriesId
        },
        name = cover.title.name,
        posterUrl = when (val title = cover.title) {
            is VodTitle.OfMovie -> title.movie.posterUrl
            is VodTitle.OfSeries -> title.series.posterUrl
        },
        year = when (val title = cover.title) {
            is VodTitle.OfMovie -> title.movie.year
            is VodTitle.OfSeries -> title.series.year
        },
    )

    fun toCover(): Cover = Cover(
        if (series) {
            VodTitle.OfSeries(
                Series(
                    id = id, sourceId = sourceId, seriesId = providerId, name = name, categoryId = null,
                    posterUrl = posterUrl, rating = null, year = year, plot = null,
                ),
            )
        } else {
            VodTitle.OfMovie(
                Movie(
                    id = id, sourceId = sourceId, streamId = providerId, name = name, categoryId = null,
                    posterUrl = posterUrl, rating = null, year = year, plot = null, durationSeconds = null,
                    containerExtension = null, streamUrl = "",
                ),
            )
        },
    )
}

@Serializable
private data class SavedLive(
    /** Null when the channel had already gone, so the tile still says so. */
    val channel: SavedChannelRow?,
    val name: String,
    val logoUrl: String?,
    val nowTitle: String?,
    val nextTitle: String?,
) {
    constructor(live: LiveNow) : this(
        live.preview.channel?.let(::SavedChannelRow),
        live.preview.name,
        live.preview.logoUrl,
        live.nowTitle,
        live.nextTitle,
    )

    fun toLive(): LiveNow = LiveNow(
        channel?.let {
            LivePreview.Playable(it.toChannel())
        } ?: LivePreview.Gone(name, logoUrl),
        nowTitle,
        nextTitle,
    )
}

@Serializable
private data class SavedChannel(val row: SavedChannelRow, val key: String, val nowOn: String?) {
    constructor(home: HomeChannel) : this(SavedChannelRow(home.channel.primary), home.channel.key, home.nowOn)

    fun toHomeChannel(): HomeChannel {
        val channel = row.toChannel()
        return HomeChannel(LineupChannel(channel, listOf(channel), favourite = true, key = key), nowOn)
    }
}

/** What a channel's card draws, and its row id to play it by. */
@Serializable
private data class SavedChannelRow(
    val id: Long,
    val sourceId: Long,
    val streamId: String,
    val name: String,
    val displayName: String,
    val customName: String?,
    val groupKey: String,
    val qualityRank: Int,
    val qualityLabel: String,
    val logoUrl: String?,
    val number: Int?,
) {
    constructor(channel: Channel) : this(
        channel.id, channel.sourceId, channel.streamId, channel.name, channel.displayName, channel.customName,
        channel.groupKey, channel.qualityRank, channel.qualityLabel, channel.logoUrl, channel.number,
    )

    fun toChannel() = Channel(
        id = id, sourceId = sourceId, streamId = streamId, name = name, displayName = displayName,
        customName = customName, groupKey = groupKey, qualityRank = qualityRank, qualityLabel = qualityLabel,
        categoryId = null, logoUrl = logoUrl, epgChannelId = null, number = number, streamUrl = "",
    )
}
