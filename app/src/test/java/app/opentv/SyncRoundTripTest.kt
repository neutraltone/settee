/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import app.opentv.SyncDevice.Companion.EPISODE_MILLIS
import app.opentv.SyncDevice.Companion.MOVIE_MILLIS
import app.opentv.SyncDevice.Companion.NAS_RECORDING
import app.opentv.SyncDevice.Companion.PASSWORD
import app.opentv.SyncDevice.Companion.USERNAME
import app.opentv.data.model.Recording
import app.opentv.data.repo.MediaKey
import app.opentv.data.repo.Playable
import app.opentv.data.repo.WatchStatus
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.flow.first
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

    private val deviceA = SyncDevice(app, rowIdOffset = 0)
    private val deviceB = SyncDevice(app, rowIdOffset = 100)
    private val deviceC = SyncDevice(app, rowIdOffset = 200)

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
}
