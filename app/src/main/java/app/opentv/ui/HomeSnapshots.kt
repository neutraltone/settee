/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv.ui

import app.opentv.data.model.Channel
import app.opentv.data.model.Episode
import app.opentv.data.model.Movie
import app.opentv.data.model.Series
import app.opentv.data.repo.CoverStatus
import app.opentv.data.repo.HomeArrangement
import app.opentv.data.repo.LineupChannel
import app.opentv.data.repo.LivePreview
import app.opentv.data.repo.MediaKey
import app.opentv.data.repo.VodTitle
import java.io.File
import java.util.concurrent.ConcurrentHashMap
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
            becauseYouWatched = home.becauseYouWatched?.let(::SavedBecause),
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
            becauseYouWatched = saved.becauseYouWatched?.toBecause(),
        )
    }

    /** The Profiles whose saved Home has been read for a start since Settee started. */
    private val started = ConcurrentHashMap.newKeySet<Long>()

    /**
     * [profileId]'s saved Home for its first Home since Settee started; null every time after. Home
     * coming back later, after a player or the background, waits for fresh rows rather than drawing old
     * ones, where it would highlight a card that's about to move or go (#192).
     */
    suspend fun readOnStart(profileId: Long): HomeState? = if (started.add(profileId)) read(profileId) else null

    /** Forgets [profileId]'s saved Home, as when the Profile is removed: a new one can be given its id. */
    suspend fun forget(profileId: Long) {
        withContext(Dispatchers.IO) { file(profileId).delete() }
    }

    /** Forgets every Profile's saved Home, as when a provider is removed: another can be given its row ids. */
    suspend fun forgetAll() {
        withContext(Dispatchers.IO) { dir.listFiles()?.forEach { it.delete() } }
    }

    private fun file(profileId: Long) = File(dir, "$profileId.json")

    private companion object {
        /** Raised whenever what's saved changes meaning, so an old file is passed over rather than misread. */
        const val FORMAT = 2
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
    val becauseYouWatched: SavedBecause?,
)

@Serializable
private data class SavedBecause(val basedOn: String, val covers: List<SavedCover>) {
    constructor(because: BecauseYouWatched) : this(because.basedOn, because.covers.map(::SavedCover))

    fun toBecause() = BecauseYouWatched(basedOn, covers.map(SavedCover::toCover))
}

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
    val status: SavedStatus? = null,
) {
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
        status?.toStatus(),
    )
}

private fun SavedCover(cover: Cover): SavedCover {
    val status = cover.status?.let(::SavedStatus)
    return when (val title = cover.title) {
        is VodTitle.OfMovie -> with(title.movie) {
            SavedCover(false, id, sourceId, streamId, name, posterUrl, year, status)
        }
        is VodTitle.OfSeries -> with(title.series) {
            SavedCover(true, id, sourceId, seriesId, name, posterUrl, year, status)
        }
    }
}

/** A cover's status as it draws it: how far, how long left, and which episode. */
@Serializable
private data class SavedStatus(
    val kind: Kind,
    val progress: Float? = null,
    val remainingMillis: Long? = null,
    val episode: SavedEpisode? = null,
) {
    enum class Kind { IN_PROGRESS, UP_NEXT, NEW_EPISODE, WATCHED }

    constructor(status: CoverStatus) : this(
        kind = when (status) {
            is CoverStatus.InProgress -> Kind.IN_PROGRESS
            is CoverStatus.UpNext -> Kind.UP_NEXT
            CoverStatus.NewEpisode -> Kind.NEW_EPISODE
            CoverStatus.Watched -> Kind.WATCHED
        },
        progress = (status as? CoverStatus.InProgress)?.progress,
        remainingMillis = (status as? CoverStatus.InProgress)?.remainingMillis,
        episode = when (status) {
            is CoverStatus.InProgress -> status.episode
            is CoverStatus.UpNext -> status.episode
            else -> null
        }?.let(::SavedEpisode),
    )

    fun toStatus(): CoverStatus? = when (kind) {
        Kind.IN_PROGRESS -> progress?.let { CoverStatus.InProgress(it, remainingMillis, episode?.toEpisode()) }
        Kind.UP_NEXT -> episode?.let { CoverStatus.UpNext(it.toEpisode()) }
        Kind.NEW_EPISODE -> CoverStatus.NewEpisode
        Kind.WATCHED -> CoverStatus.Watched
    }
}

@Serializable
private data class SavedEpisode(
    val id: Long,
    val sourceId: Long,
    val seriesId: String,
    val episodeId: String,
    val season: Int,
    val number: Int,
    val title: String,
) {
    constructor(episode: Episode) : this(
        episode.id,
        episode.sourceId,
        episode.seriesId,
        episode.episodeId,
        episode.season,
        episode.episodeNumber,
        episode.title,
    )

    fun toEpisode() = Episode(
        id = id, sourceId = sourceId, seriesId = seriesId, episodeId = episodeId, season = season,
        episodeNumber = number, title = title, plot = null, durationSeconds = null, stillUrl = null, streamUrl = "",
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
