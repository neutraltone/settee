/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv

import android.app.Application
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
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
import app.opentv.data.repo.MediaKey
import app.opentv.data.repo.Playable
import app.opentv.data.repo.WatchStatus
import app.opentv.sync.SyncEngine
import app.opentv.sync.SyncFavourite
import app.opentv.sync.SyncProfileSetting
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

// Plain Application: OpenTvApp schedules WorkManager on start, which sync doesn't need.
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class SyncRoundTripTest {

    private val app: Application = ApplicationProvider.getApplicationContext()

    /**
     * One device: its own database and settings, holding the same provider's catalogue under its
     * own row ids.
     */
    private inner class Device(private val rowIdOffset: Long) {
        val db: OpenTvDatabase = Room.inMemoryDatabaseBuilder(app, OpenTvDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        val panel = FakePanel()
        val graph = ServiceLocator.Graph(app, panel.http, settingsFile = "device_$rowIdOffset") { db }
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

        suspend fun hide(n: Int) = db.channels().upsertAll(listOf(channels[n - 1].copy(hidden = true)))

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
    }

    private val deviceA = Device(rowIdOffset = 0)
    private val deviceB = Device(rowIdOffset = 100)
    private val deviceC = Device(rowIdOffset = 200)

    @After
    fun close() {
        runBlocking { deviceB.awaitEpisodes() }
        deviceA.db.close()
        deviceB.db.close()
        deviceC.db.close()
    }

    /** Sends A's state to B the way the transports do: gathered, as JSON, then applied. */
    private suspend fun syncAToB() = deviceB.sync.apply(bundleFromA())

    private suspend fun bundleFromA() = deviceB.sync.decode(deviceA.sync.encode(deviceA.sync.gather()))

    @Test
    fun `each Profile's Favourites land on the Profile with the same name on the other device`() = runTest {
        deviceA.seed("Alex", "Sam")
        // B made its Profiles in the other order, so the same name has a different row id there.
        deviceB.seed("Sam", "Alex")
        deviceA.favourite("Alex", 1)
        deviceA.favourite("Sam", 2)

        syncAToB()

        assertThat(deviceB.favouriteNames("Alex")).containsExactly("Channel 1", "Movie 1", "Series 1")
        assertThat(deviceB.favouriteNames("Sam")).containsExactly("Channel 2", "Movie 2", "Series 2")
    }

    @Test
    fun `a Profile only the other device has arrives with its Favourites`() = runTest {
        deviceA.seed("Alex", "Sam")
        deviceB.seed("Alex")
        deviceA.favourite("Sam", 1)

        syncAToB()

        assertThat(deviceB.hasProfile("Sam")).isTrue()
        assertThat(deviceB.favouriteNames("Sam")).containsExactly("Channel 1", "Movie 1", "Series 1")
    }

    @Test
    fun `syncing keeps the Favourites only this device has and adds nothing twice`() = runTest {
        deviceA.seed("Alex")
        deviceB.seed("Alex")
        deviceA.favourite("Alex", 1)
        deviceB.favouriteChannel("Alex", 2)
        val bundle = bundleFromA()

        val first = deviceB.sync.apply(bundle)
        val again = deviceB.sync.apply(bundle)

        assertThat(deviceB.favouriteNames("Alex"))
            .containsExactly("Channel 1", "Channel 2", "Movie 1", "Series 1")
        assertThat(first.favourites).isEqualTo(3)
        assertThat(again.favourites).isEqualTo(0)
    }

    @Test
    fun `progress and a title marked watched carry over to the same Profile, and only that Profile`() = runTest {
        deviceA.seed("Alex", "Sam")
        deviceB.seed("Sam", "Alex")
        deviceA.play("Alex", deviceA.movieKey, 1_200_000, MOVIE_MILLIS)
        // Watched the way the player records it when it moves on: progress at the full length.
        // Swap in the options menu's mark watched (#15) once it is on main.
        deviceA.play("Alex", deviceA.episodeKey, EPISODE_MILLIS, EPISODE_MILLIS)

        syncAToB()

        assertThat(deviceB.resumeFrom("Alex", deviceB.movieKey)).isEqualTo(1_200_000)
        assertThat(deviceB.statusOf("Alex", deviceB.episode.id)).isEqualTo(WatchStatus.Watched)
        assertThat(deviceB.startedMovies("Sam")).isEmpty()
        assertThat(deviceB.statusOf("Sam", deviceB.episode.id)).isEqualTo(WatchStatus.Unwatched)
    }

    @Test
    fun `whichever device played a title more recently decides where it resumes`() = runTest {
        deviceA.seed("Alex")
        deviceB.seed("Alex")
        // B watched the movie last; A watched the episode last.
        deviceA.recordOldProgress("Alex", deviceA.movieKey, positionMillis = 600_000)
        deviceB.play("Alex", deviceB.movieKey, 3_000_000, MOVIE_MILLIS)
        deviceB.recordOldProgress("Alex", deviceB.episodeKey, positionMillis = 300_000)
        deviceA.play("Alex", deviceA.episodeKey, 900_000, EPISODE_MILLIS)

        val result = syncAToB()

        assertThat(deviceB.resumeFrom("Alex", deviceB.movieKey)).isEqualTo(3_000_000)
        assertThat(deviceB.resumeFrom("Alex", deviceB.episodeKey)).isEqualTo(900_000)
        assertThat(result.positions).isEqualTo(1)
    }

    @Test
    fun `progress still matches after the provider password changes on one device`() = runTest {
        deviceA.seed("Alex")
        deviceB.seed("Alex")
        deviceA.play("Alex", deviceA.movieKey, 1_200_000, MOVIE_MILLIS)
        deviceA.play("Alex", deviceA.episodeKey, 900_000, EPISODE_MILLIS)
        deviceA.changePassword("passSyncFixtureChanged")

        val result = syncAToB()

        assertThat(deviceB.resumeFrom("Alex", deviceB.movieKey)).isEqualTo(1_200_000)
        assertThat(deviceB.resumeFrom("Alex", deviceB.episodeKey)).isEqualTo(900_000)
        assertThat(result.positions).isEqualTo(2)
    }

    @Test
    fun `hidden channels still match after the provider password changes on one device`() = runTest {
        deviceA.seed("Alex")
        deviceB.seed("Alex")
        deviceA.hide(1)
        deviceA.changePassword("passSyncFixtureChanged")

        val result = syncAToB()

        assertThat(deviceB.isHidden(1)).isTrue()
        assertThat(deviceB.isHidden(2)).isFalse()
        assertThat(result.hidden).isEqualTo(1)
    }

    @Test
    fun `hidden channels still match after the live format changes on one device`() = runTest {
        deviceA.seed("Alex")
        deviceB.seed("Alex")
        deviceA.hide(2)
        deviceA.changeLiveFormat()

        syncAToB()

        assertThat(deviceB.isHidden(2)).isTrue()
    }

    @Test
    fun `a bundle carries no provider credentials or host`() = runTest {
        deviceA.seed("Alex")
        deviceA.play("Alex", deviceA.movieKey, 1_200_000, MOVIE_MILLIS)
        deviceA.play("Alex", deviceA.episodeKey, 900_000, EPISODE_MILLIS)
        deviceA.favourite("Alex", 1)
        deviceA.hide(1)
        deviceA.recordToNas()

        val encoded = deviceA.sync.encode(deviceA.sync.gather())

        assertThat(encoded).contains(NAS_RECORDING)
        assertThat(encoded).doesNotContain(USERNAME)
        assertThat(encoded).doesNotContain(PASSWORD)
        assertThat(encoded).doesNotContain("panel-sync-fixture")
    }

    @Test
    fun `a NAS recording from another device appears in the library and plays from the NAS`() = runTest {
        deviceA.seed("Alex")
        deviceB.seed("Alex")
        deviceA.recordToNas()

        val result = syncAToB()

        val received = deviceB.db.recordings().byFilePath(NAS_RECORDING)
        assertThat(result.recordings).isEqualTo(1)
        assertThat(received?.title).isEqualTo("The Match")
        assertThat(received?.streamUrl).isEmpty()
        assertThat(received?.logoUrl).isNull()
        val playable = deviceB.graph.playbackSources.forWatching(MediaKey.Recording(checkNotNull(received).id))
        assertThat((playable as Playable.Ready).url).isEqualTo(NAS_RECORDING)
    }

    @Test
    fun `an old-format bundle still merges its Favourites and ignores its positions and hidden channels`() = runTest {
        deviceB.seed("Alex")
        val oldBundle = """
            {
              "version": 5,
              "profiles": ["Alex"],
              "positions": [{
                "profile": "Alex", "streamUrl": "http://example.com/movie/u/p/m1.mkv", "kind": "movie",
                "positionMillis": 1200000, "durationMillis": 6000000, "updatedAtMillis": 1000
              }],
              "favourites": [
                {"profile": "Alex", "kind": "LIVE", "source": "Provider", "id": "c1"}
              ],
              "hiddenChannels": ["http://example.com/live/u/p/c2.ts"],
              "recordings": [{
                "channelName": "Channel 1", "logoUrl": "http://example.com/c1.png", "title": "The Match",
                "filePath": "smb://nas.example.com/share/old.ts",
                "streamUrl": "http://example.com/live/u/p/c1.ts", "userAgent": "Settee"
              }]
            }
        """.trimIndent()

        val result = deviceB.sync.apply(deviceB.sync.decode(oldBundle))

        assertThat(deviceB.favouriteNames("Alex")).containsExactly("Channel 1")
        assertThat(deviceB.resumeFrom("Alex", deviceB.movieKey)).isEqualTo(0)
        assertThat(deviceB.isHidden(2)).isFalse()
        assertThat(result.positions).isEqualTo(0)
        assertThat(result.hidden).isEqualTo(0)
        assertThat(result.recordings).isEqualTo(1)
    }

    @Test
    fun `progress for an episode the receiver hasn't fetched arrives without opening the series`() = runTest {
        deviceA.seed("Alex")
        deviceB.openedSeries = false
        deviceB.seed("Alex")
        deviceB.panel.replies["get_series_info:s"] = episodesReply("1-1")
        deviceA.play("Alex", deviceA.episodeKey, 900_000, EPISODE_MILLIS)

        val result = syncAToB()
        deviceB.awaitEpisodes()

        assertThat(deviceB.resumeFromEpisode("Alex", "1-1")).isEqualTo(900_000)
        // Reported before the fetch, which the sync screen doesn't wait for.
        assertThat(result.positions).isEqualTo(0)
    }

    @Test
    fun `applying a bundle doesn't wait for the provider`() = runTest {
        deviceA.seed("Alex")
        deviceB.openedSeries = false
        deviceB.seed("Alex")
        deviceB.panel.replies["get_series_info:s"] = episodesReply("1-1")
        val hold = deviceB.panel.hold("get_series_info:s")
        deviceA.play("Alex", deviceA.episodeKey, 900_000, EPISODE_MILLIS)
        deviceA.favouriteChannel("Alex", 1)

        val result = syncAToB()

        during(hold) {
            assertThat(result.favourites).isEqualTo(1)
            assertThat(deviceB.resumeFromEpisode("Alex", "1-1")).isNull()
        }
        hold.release()
        deviceB.awaitEpisodes()
        assertThat(deviceB.resumeFromEpisode("Alex", "1-1")).isEqualTo(900_000)
    }

    @Test
    fun `a provider that's down costs one fetch, and the progress arrives on a later sync`() = runTest {
        deviceA.seed("Alex")
        deviceB.openedSeries = false
        deviceB.seed("Alex")
        val otherSeries = deviceA.addEpisode("s2", "2-1", localId = 21)
        deviceA.play("Alex", deviceA.episodeKey, 900_000, EPISODE_MILLIS)
        deviceA.play("Alex", otherSeries, 300_000, EPISODE_MILLIS)
        deviceA.favouriteChannel("Alex", 1)
        deviceB.panel.replies["get_series_info"] = "!"

        val whileDown = syncAToB()
        deviceB.awaitEpisodes()

        assertThat(whileDown.favourites).isEqualTo(1)
        assertThat(deviceB.seriesFetches()).isEqualTo(1)
        assertThat(deviceB.resumeFromEpisode("Alex", "1-1")).isNull()

        deviceB.panel.replies.remove("get_series_info")
        deviceB.panel.replies["get_series_info:s"] = episodesReply("1-1")
        deviceB.panel.replies["get_series_info:s2"] = episodesReply("2-1")
        syncAToB()
        deviceB.awaitEpisodes()

        assertThat(deviceB.resumeFromEpisode("Alex", "1-1")).isEqualTo(900_000)
        assertThat(deviceB.resumeFromEpisode("Alex", "2-1")).isEqualTo(300_000)
    }

    @Test
    fun `several positions in a series, from different peers, cause one fetch`() = runTest {
        deviceA.seed("Alex")
        deviceC.seed("Alex")
        deviceB.openedSeries = false
        deviceB.seed("Alex")
        deviceB.panel.replies["get_series_info:s"] = episodesReply("1-1", "1-2")
        deviceA.play("Alex", deviceA.episodeKey, 900_000, EPISODE_MILLIS)
        deviceC.play("Alex", deviceC.addEpisode("s", "1-2", localId = 12), 500_000, EPISODE_MILLIS)

        deviceB.sync.apply(bundleFromA(), deviceB.sync.decode(deviceC.sync.encode(deviceC.sync.gather())))
        deviceB.awaitEpisodes()

        assertThat(deviceB.seriesFetches()).isEqualTo(1)
        assertThat(deviceB.resumeFromEpisode("Alex", "1-1")).isEqualTo(900_000)
        assertThat(deviceB.resumeFromEpisode("Alex", "1-2")).isEqualTo(500_000)
    }

    @Test
    fun `a position for a source the receiver doesn't have causes no fetch and no error`() = runTest {
        deviceA.seed("Alex")
        deviceB.openedSeries = false
        deviceB.sourceName = "Someone else's provider"
        deviceB.seed("Alex")
        deviceA.play("Alex", deviceA.episodeKey, 900_000, EPISODE_MILLIS)

        val result = syncAToB()
        deviceB.awaitEpisodes()

        assertThat(result.positions).isEqualTo(0)
        assertThat(deviceB.seriesFetches()).isEqualTo(0)
    }

    @Test
    fun `an episode the provider doesn't have is asked for once, and placed if the series later has it`() = runTest {
        deviceA.seed("Alex")
        deviceB.openedSeries = false
        deviceB.seed("Alex")
        deviceB.panel.replies["get_series_info:s"] = episodesReply("9-9")
        deviceA.play("Alex", deviceA.episodeKey, 900_000, EPISODE_MILLIS)

        syncAToB()
        deviceB.awaitEpisodes()
        syncAToB()
        deviceB.awaitEpisodes()

        assertThat(deviceB.resumeFromEpisode("Alex", "1-1")).isNull()
        assertThat(deviceB.seriesFetches()).isEqualTo(1)

        deviceB.panel.replies["get_series_info:s"] = episodesReply("9-9", "1-1")
        deviceB.openSeries("s")
        syncAToB()

        assertThat(deviceB.resumeFromEpisode("Alex", "1-1")).isEqualTo(900_000)
    }

    @Test
    fun `a series the provider answers with nothing is asked for again on a later sync`() = runTest {
        deviceA.seed("Alex")
        deviceB.openedSeries = false
        deviceB.seed("Alex")
        deviceB.panel.replies["get_series_info:s"] = """{"info": {}, "episodes": {}}"""
        deviceA.play("Alex", deviceA.episodeKey, 900_000, EPISODE_MILLIS)

        syncAToB()
        deviceB.awaitEpisodes()
        deviceB.panel.replies["get_series_info:s"] = episodesReply("1-1")
        syncAToB()
        deviceB.awaitEpisodes()

        assertThat(deviceB.resumeFromEpisode("Alex", "1-1")).isEqualTo(900_000)
    }

    @Test
    fun `one sync fetches a limited number of series, and a later one fetches the rest`() = runTest {
        deviceA.seed("Alex")
        deviceB.openedSeries = false
        deviceB.seed("Alex")
        for (n in 1..30) {
            deviceA.play("Alex", deviceA.addEpisode("s$n", "e$n", localId = 1_000L + n), 900_000, EPISODE_MILLIS)
            deviceB.panel.replies["get_series_info:s$n"] = episodesReply("e$n")
        }

        syncAToB()
        deviceB.awaitEpisodes()
        assertThat(deviceB.seriesFetches()).isEqualTo(25)

        syncAToB()
        deviceB.awaitEpisodes()
        assertThat(deviceB.seriesFetches()).isEqualTo(30)
        assertThat(deviceB.resumeFromEpisode("Alex", "e30")).isEqualTo(900_000)
    }

    @Test
    fun `a provider switched off on the receiver isn't asked for episodes`() = runTest {
        deviceA.seed("Alex")
        deviceB.openedSeries = false
        deviceB.sourceEnabled = false
        deviceB.seed("Alex")
        deviceA.play("Alex", deviceA.episodeKey, 900_000, EPISODE_MILLIS)

        syncAToB()
        deviceB.awaitEpisodes()

        assertThat(deviceB.seriesFetches()).isEqualTo(0)
    }

    @Test
    fun `episodes a catch-up fetch brings in aren't New Episodes`() = runTest {
        deviceA.seed("Alex")
        deviceB.openedSeries = false
        deviceB.seed("Alex")
        deviceB.panel.replies["get_series_info:s"] = episodesReply("1-1", "1-2")
        deviceA.recordOldProgress("Alex", deviceA.episodeKey, positionMillis = MOVIE_MILLIS)

        syncAToB()
        deviceB.awaitEpisodes()

        val card = deviceB.upNext("Alex")
        assertThat(card.episode.episodeId).isEqualTo("1-2")
        assertThat(card.newEpisode).isFalse()
    }

    @Test
    fun `an episode the provider adds after the catch-up fetch is a New Episode`() = runTest {
        deviceA.seed("Alex")
        deviceB.openedSeries = false
        deviceB.seed("Alex")
        deviceB.panel.replies["get_series_info:s"] = episodesReply("1-1")
        deviceA.recordOldProgress("Alex", deviceA.episodeKey, positionMillis = MOVIE_MILLIS)
        syncAToB()
        deviceB.awaitEpisodes()

        deviceB.panel.replies["get_series_info:s"] = episodesReply("1-1", "1-2")
        deviceB.openSeries("s")

        val card = deviceB.upNext("Alex")
        assertThat(card.episode.episodeId).isEqualTo("1-2")
        assertThat(card.newEpisode).isTrue()
    }

    private fun episodesReply(vararg ids: String): String {
        val episodes = ids.withIndex().joinToString { (index, id) ->
            """{"id": "$id", "episode_num": ${index + 1}, "title": "Episode"}"""
        }
        return """{"info": {}, "episodes": {"1": [$episodes]}}"""
    }

    @Test
    fun `a Profile removed here doesn't come back from another device or an old NAS file`() = runTest {
        deviceA.seed("Tom")
        deviceB.seed("Tom")
        deviceA.favourite("Tom", 1)
        val oldFile = bundleFromA()
        syncAToB()

        deviceB.graph.profiles.remove(deviceB.profileId("Tom"))
        syncAToB()
        deviceB.sync.apply(oldFile)

        assertThat(deviceB.hasProfile("Tom")).isFalse()
        assertThat(deviceB.db.favourites().all()).isEmpty()
        assertThat(deviceA.hasProfile("Tom")).isTrue()
        assertThat(deviceA.favouriteNames("Tom")).containsExactly("Channel 1", "Movie 1", "Series 1")
    }

    @Test
    fun `adding a removed name back brings its history from the other device`() = runTest {
        deviceA.seed("Tom")
        deviceB.seed("Tom")
        deviceA.favourite("Tom", 1)
        deviceB.graph.profiles.remove(deviceB.profileId("Tom"))

        deviceB.graph.profiles.create("Tom")
        syncAToB()

        assertThat(deviceB.favouriteNames("Tom")).containsExactly("Channel 1", "Movie 1", "Series 1")
    }

    @Test
    fun `a new Profile of a removed name on another device doesn't lift the removal here`() = runTest {
        deviceB.seed("Tom")
        deviceB.graph.profiles.remove(deviceB.profileId("Tom"))

        deviceA.seed()
        deviceA.graph.profiles.create("Tom")
        syncAToB()

        assertThat(deviceB.hasProfile("Tom")).isFalse()
    }

    @Test
    fun `a Profile removed while a sync is applying is left with no rows under its id`() = runBlocking {
        deviceA.seed("Tom")
        deviceB.seed("Tom")
        val tom = deviceB.profileId("Tom")
        val bundle = bundleFromA().copy(
            favourites = List(RACE_ITEMS) { SyncFavourite("Tom", "MOVIE", "Provider", "race$it") },
            profileSettings = List(RACE_ITEMS) { SyncProfileSetting("Tom", "setting$it", "value", 1) },
        )

        val applying = launch(Dispatchers.IO) { deviceB.sync.apply(bundle) }
        while (deviceB.db.favourites().all().isEmpty()) delay(1)
        deviceB.graph.profiles.remove(tom)
        applying.join()

        assertThat(deviceB.db.favourites().all().filter { it.profileId == tom }).isEmpty()
        assertThat(deviceB.db.profileSettings().all().filter { it.profileId == tom }).isEmpty()
    }

    @Test
    fun `a Profile removed while its episodes are being fetched is left with no rows under its id`() = runTest {
        deviceA.seed("Tom")
        deviceB.openedSeries = false
        deviceB.seed("Tom")
        deviceB.panel.replies["get_series_info:s"] = episodesReply("1-1")
        val hold = deviceB.panel.hold("get_series_info:s")
        deviceA.play("Tom", deviceA.episodeKey, 900_000, EPISODE_MILLIS)
        val tom = deviceB.profileId("Tom")

        syncAToB()
        during(hold) { deviceB.graph.profiles.remove(tom) }
        hold.release()
        deviceB.awaitEpisodes()

        assertThat(deviceB.db.positions().all().filter { it.profileId == tom }).isEmpty()
    }

    private companion object {
        const val RACE_ITEMS = 3_000
        const val NAS_RECORDING = "smb://nas.example.com/share/Settee/the-match.ts"

        // Distinctive, because a bundle is searched for them. The user/pass prefixes satisfy gitleaks.
        const val USERNAME = "userSyncFixture"
        const val PASSWORD = "passSyncFixture"
        const val PANEL_URL = "http://panel-sync-fixture.example.com"
        const val MOVIE_MILLIS = 6_000_000L
        const val EPISODE_MILLIS = 2_400_000L
    }
}
