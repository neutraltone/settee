/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv

import android.app.Application
import androidx.room.Room
import app.opentv.core.ServiceLocator
import app.opentv.data.db.OpenTvDatabase
import app.opentv.data.model.Channel
import app.opentv.data.model.Movie
import app.opentv.data.model.PlaybackPosition
import app.opentv.data.model.Recording
import app.opentv.data.model.RecordingStatus
import app.opentv.data.model.Series
import app.opentv.data.model.Source
import app.opentv.data.model.SourceKind
import app.opentv.data.repo.ContinueWatchingCard
import app.opentv.data.repo.WatchStatus
import app.opentv.sync.SyncEngine
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.flow.first

/**
 * One device: its own database and settings, holding the same provider's catalogue under its
 * own row ids.
 */
internal class SyncDevice(app: Application, private val rowIdOffset: Long) {
    /** What this device's clock reads, once a test cares which device changed something last. */
    var now: Long? = null

    private fun clock(): Long = now ?: System.currentTimeMillis()
    val db: OpenTvDatabase = Room.inMemoryDatabaseBuilder(app, OpenTvDatabase::class.java)
        .allowMainThreadQueries()
        .build()
    val panel = FakePanel()
    val graph = ServiceLocator.Graph(
        app,
        panel.http,
        settingsFile = "device_$rowIdOffset",
        clock = ::clock,
    ) { db }
    val sync = SyncEngine(graph)
    private val library get() = graph.viewerLibrary

    val sourceId = 1 + rowIdOffset
    val channels = (1..2).map { n ->
        Channel(
            id = n + rowIdOffset,
            sourceId = sourceId,
            streamId = "c$n",
            name = "Channel $n",
            categoryId = null,
            logoUrl = null,
            epgChannelId = null,
            number = n,
            streamUrl = "$PANEL_URL/live/$USERNAME/$PASSWORD/c$n.ts",
        )
    }
    val movies = (1..2).map { n ->
        Movie(
            id = n + rowIdOffset,
            sourceId = sourceId,
            streamId = "m$n",
            name = "Movie $n",
            categoryId = null,
            posterUrl = null,
            rating = null,
            year = null,
            plot = null,
            durationSeconds = 6_000,
            containerExtension = "mkv",
            streamUrl = "$PANEL_URL/movie/$USERNAME/$PASSWORD/m$n.mkv",
        )
    }
    val series = (1..2).map { n ->
        Series(
            id = n + rowIdOffset,
            sourceId = sourceId,
            seriesId = if (n == 1) "s" else "s$n",
            name = "Series $n",
            categoryId = null,
            posterUrl = null,
            rating = null,
            year = null,
            plot = null,
        )
    }
    val episode = testEpisode(1, 1).copy(
        id = 11 + rowIdOffset,
        sourceId = sourceId,
        streamUrl = "$PANEL_URL/series/$USERNAME/$PASSWORD/e1.mkv",
    )
    val movieKey = "movie:${movies[0].id}"
    val episodeKey = "ep:${episode.id}"

    /** Set before [seed]. A series' episodes are only stored once it has been opened. */
    var openedSeries = true
    var sourceName = "Provider"
    var sourceEnabled = true

    suspend fun seed(vararg profiles: String) {
        db.sources().insert(
            Source(
                id = sourceId,
                name = sourceName,
                enabled = sourceEnabled,
                kind = SourceKind.XTREAM,
                url = PANEL_URL,
                username = USERNAME,
                password = PASSWORD,
            ),
        )
        db.channels().upsertAll(channels)
        db.movies().upsertAll(movies)
        db.series().upsertAll(series)
        if (openedSeries) db.episodes().upsertAll(listOf(episode))
        profiles.forEach { checkNotNull(graph.profiles.create(it)) }
    }

    /** What a provider password change does to the rows on this device: every stream URL changes. */
    suspend fun changePassword(password: String) {
        channels.forEach { db.channels().updateStreamUrl(it.id, it.streamUrl.replace(PASSWORD, password)) }
        db.movies().upsertAll(movies.map { it.copy(streamUrl = it.streamUrl.replace(PASSWORD, password)) })
        db.episodes().upsertAll(listOf(episode.copy(streamUrl = episode.streamUrl.replace(PASSWORD, password))))
    }

    /** What a live format change does: only the channels' URLs change. */
    suspend fun changeLiveFormat() {
        channels.forEach { db.channels().updateStreamUrl(it.id, it.streamUrl.replace(".ts", ".m3u8")) }
    }

    suspend fun hide(n: Int) = graph.catalogRepository.setChannelHidden(channels[n - 1].id, true, clock())

    suspend fun show(n: Int) = graph.catalogRepository.setChannelHidden(channels[n - 1].id, false, clock())

    suspend fun unfavourite(name: String, n: Int) {
        asProfile(name)
        library.favourites.set(movies[n - 1], favourite = false)
    }

    suspend fun isHidden(n: Int): Boolean = checkNotNull(db.channels().byId(channels[n - 1].id)).hidden

    /** A finished recording on the shared NAS, captured from this device's provider. */
    suspend fun recordToNas() {
        graph.recordingRepository.insert(
            Recording(
                channelId = channels[0].id,
                sourceId = sourceId,
                channelName = "Channel 1",
                logoUrl = "$PANEL_URL/logos/c1.png",
                title = "The Match",
                filePath = NAS_RECORDING,
                streamUrl = channels[0].streamUrl,
                userAgent = "Settee",
                status = RecordingStatus.COMPLETED,
            ),
        )
    }

    /** Stores another episode, as opening its series does, and returns its media key. */
    suspend fun addEpisode(seriesId: String, episodeId: String, localId: Long): String {
        db.episodes().upsertAll(
            listOf(episode.copy(id = localId + rowIdOffset, seriesId = seriesId, episodeId = episodeId)),
        )
        return "ep:${localId + rowIdOffset}"
    }

    /** Where [name] resumes the provider's episode [episodeId], or null if it isn't stored here. */
    suspend fun resumeFromEpisode(name: String, episodeId: String): Long? {
        val stored = db.episodes().byProviderId(sourceId, episodeId) ?: return null
        return resumeFrom(name, "ep:${stored.id}")
    }

    suspend fun upNext(name: String): ContinueWatchingCard.Series {
        asProfile(name)
        return library.continueWatchingSeries().first().single() as ContinueWatchingCard.Series
    }

    /** Fetches a series' episodes the way opening it does. */
    suspend fun openSeries(seriesId: String) {
        graph.catalogRepository.ensureEpisodes(checkNotNull(db.sources().byId(sourceId)), seriesId)
    }

    fun seriesFetches() = panel.asked.count { it.startsWith("get_series_info") }

    suspend fun awaitEpisodes() = graph.waitingProgress.awaitBackground()

    suspend fun rename(from: String, to: String) = assertThat(graph.profiles.rename(profileId(from), to)).isTrue()

    suspend fun profileNames(): List<String> = graph.profiles.all().map { it.name }

    suspend fun syncFrom(other: SyncDevice) = sync.apply(sync.decode(other.sync.encode(other.sync.gather())))

    suspend fun hasProfile(name: String): Boolean = graph.profiles.all().any { it.name == name }

    suspend fun profileId(name: String): Long = graph.profiles.all().single { it.name == name }.id

    /** Acts as [name] on this device, the way picking a Profile does. */
    suspend fun asProfile(name: String) = graph.settings.setActiveProfile(profileId(name))

    /** Makes the [n]th channel, movie and series [name]'s Favourites. */
    suspend fun favourite(name: String, n: Int) {
        asProfile(name)
        library.favourites.set(channels[n - 1], favourite = true)
        library.favourites.set(movies[n - 1], favourite = true)
        library.favourites.set(series[n - 1], favourite = true)
    }

    suspend fun favouriteChannel(name: String, n: Int) {
        asProfile(name)
        library.favourites.set(channels[n - 1], favourite = true)
    }

    suspend fun favouriteNames(name: String): List<String> {
        asProfile(name)
        return library.favourites.channels().first().map { it.name } +
            library.favourites.movies().first().map { it.name } +
            library.favourites.series().first().map { it.name }
    }

    suspend fun play(name: String, mediaKey: String, positionMillis: Long, durationMillis: Long) {
        asProfile(name)
        library.recordProgress(mediaKey, positionMillis, durationMillis)
    }

    /** Progress [name] made long ago, so whatever the other device has is newer. */
    suspend fun recordOldProgress(name: String, mediaKey: String, positionMillis: Long) {
        library.mergeSynced(
            PlaybackPosition(profileId(name), mediaKey, positionMillis, MOVIE_MILLIS, updatedAtMillis = 1_000),
        )
    }

    suspend fun resumeFrom(name: String, mediaKey: String): Long {
        asProfile(name)
        return library.resumeFrom(mediaKey)
    }

    suspend fun startedMovies(name: String): Set<Long> = library.startedMovieIds(profileId(name))

    suspend fun statusOf(name: String, episode: Long): WatchStatus {
        asProfile(name)
        return library.episodeStatuses().first()[episode]
    }

    companion object {
        // Distinctive, because a bundle is searched for them. The user/pass prefixes satisfy gitleaks.
        const val USERNAME = "userSyncFixture"
        const val PASSWORD = "passSyncFixture"
        const val PANEL_URL = "http://panel-sync-fixture.example.com"
        const val NAS_RECORDING = "smb://nas.example.com/share/Settee/the-match.ts"
        const val MOVIE_MILLIS = 6_000_000L
        const val EPISODE_MILLIS = 2_400_000L
    }
}

internal fun episodesReply(vararg ids: String): String {
    val episodes = ids.withIndex().joinToString { (index, id) ->
        """{"id": "$id", "episode_num": ${index + 1}, "title": "Episode"}"""
    }
    return """{"info": {}, "episodes": {"1": [$episodes]}}"""
}
