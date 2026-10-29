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
import app.opentv.data.model.Profile
import app.opentv.data.model.Series
import app.opentv.data.model.Source
import app.opentv.data.model.SourceKind
import app.opentv.data.repo.WatchStatus
import app.opentv.sync.SyncEngine
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.flow.first
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

    /** One box: its own database, holding the same provider's catalogue under its own row ids. */
    private inner class Device(rowIdOffset: Long) {
        val db: OpenTvDatabase = Room.inMemoryDatabaseBuilder(app, OpenTvDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        val graph = ServiceLocator.Graph(app) { db }
        val sync = SyncEngine(graph)

        val sourceId = 1 + rowIdOffset
        val channel = Channel(
            id = 1 + rowIdOffset,
            sourceId = sourceId,
            streamId = "c1",
            name = "Channel One",
            categoryId = null,
            logoUrl = null,
            epgChannelId = null,
            number = 1,
            streamUrl = "https://example.com/live/c1.ts",
        )
        val movie = Movie(
            id = 1 + rowIdOffset,
            sourceId = sourceId,
            streamId = "m1",
            name = "A Movie",
            categoryId = null,
            posterUrl = null,
            rating = null,
            year = null,
            plot = null,
            durationSeconds = 6_000,
            containerExtension = "mkv",
            streamUrl = "https://example.com/movie/m1.mkv",
        )
        val series = Series(
            id = 1 + rowIdOffset,
            sourceId = sourceId,
            seriesId = "s",
            name = "A Series",
            categoryId = null,
            posterUrl = null,
            rating = null,
            year = null,
            plot = null,
        )
        val episode = testEpisode(1, 1).copy(id = 11 + rowIdOffset, sourceId = sourceId)

        suspend fun seed(vararg profiles: String) {
            db.sources().insert(
                Source(id = sourceId, name = "Provider", kind = SourceKind.M3U, url = "https://example.com"),
            )
            db.channels().upsertAll(listOf(channel))
            db.movies().upsertAll(listOf(movie))
            db.series().upsertAll(listOf(series))
            db.episodes().upsertAll(listOf(episode))
            profiles.forEach { db.profiles().insert(Profile(name = it)) }
        }

        suspend fun profileId(name: String): Long = checkNotNull(db.profiles().byName(name)).id

        /** Acts as [name] on this box, the way picking a Profile does. */
        suspend fun asProfile(name: String) = graph.settings.setActiveProfile(profileId(name))

        suspend fun favouriteNames(name: String): List<String> {
            asProfile(name)
            val favourites = graph.viewerLibrary.favourites
            return favourites.channels().first().map { it.name } +
                favourites.movies().first().map { it.name } +
                favourites.series().first().map { it.name }
        }

        /** Progress [name] made long ago, so whatever the other device has is newer. */
        suspend fun hadOldProgress(name: String, mediaKey: String, positionMillis: Long) {
            graph.viewerLibrary.mergeSynced(
                PlaybackPosition(profileId(name), mediaKey, positionMillis, MOVIE_MILLIS, updatedAtMillis = 1_000),
            )
        }

        suspend fun resumeFrom(name: String, mediaKey: String): Long {
            asProfile(name)
            return graph.viewerLibrary.resumeFrom(mediaKey)
        }

        suspend fun statusOf(name: String, episode: Long): WatchStatus {
            asProfile(name)
            return graph.viewerLibrary.episodeStatuses().first()[episode]
        }
    }

    private val deviceA = Device(rowIdOffset = 0)
    private val deviceB = Device(rowIdOffset = 100)

    @After
    fun close() {
        deviceA.db.close()
        deviceB.db.close()
        deviceA.graph.settings.setActiveProfile(1L)
    }

    /** Sends A's state to B the way the transports do: gathered, as JSON, then applied. */
    private suspend fun syncAToB() = deviceB.sync.apply(deviceB.sync.decode(deviceA.sync.encode(deviceA.sync.gather())))

    @Test
    fun `each Profile's Favourites land on the Profile with the same name on the other device`() = runTest {
        deviceA.seed("Alex", "Sam")
        // B made its Profiles in the other order, so the same name has a different row id there.
        deviceB.seed("Sam", "Alex")
        deviceA.asProfile("Alex")
        deviceA.graph.viewerLibrary.favourites.set(deviceA.channel, favourite = true)
        deviceA.graph.viewerLibrary.favourites.set(deviceA.movie, favourite = true)
        deviceA.asProfile("Sam")
        deviceA.graph.viewerLibrary.favourites.set(deviceA.series, favourite = true)

        syncAToB()

        assertThat(deviceB.favouriteNames("Alex")).containsExactly("Channel One", "A Movie")
        assertThat(deviceB.favouriteNames("Sam")).containsExactly("A Series")
    }

    @Test
    fun `a Profile only the other device has arrives with its Favourites`() = runTest {
        deviceA.seed("Alex", "Sam")
        deviceB.seed("Alex")
        deviceA.asProfile("Sam")
        deviceA.graph.viewerLibrary.favourites.set(deviceA.movie, favourite = true)

        syncAToB()

        assertThat(deviceB.favouriteNames("Sam")).containsExactly("A Movie")
    }

    @Test
    fun `syncing keeps the Favourites only this device has and adds nothing twice`() = runTest {
        deviceA.seed("Alex")
        deviceB.seed("Alex")
        deviceA.asProfile("Alex")
        deviceA.graph.viewerLibrary.favourites.set(deviceA.movie, favourite = true)
        deviceB.asProfile("Alex")
        deviceB.graph.viewerLibrary.favourites.set(deviceB.channel, favourite = true)

        val first = syncAToB()
        val again = syncAToB()

        assertThat(deviceB.favouriteNames("Alex")).containsExactly("Channel One", "A Movie")
        assertThat(first.favourites).isEqualTo(1)
        assertThat(again.favourites).isEqualTo(0)
    }

    @Test
    fun `progress and a title marked watched carry over to the same Profile, and only that Profile`() = runTest {
        deviceA.seed("Alex", "Sam")
        deviceB.seed("Sam", "Alex")
        deviceA.asProfile("Alex")
        deviceA.graph.viewerLibrary.recordProgress("movie:${deviceA.movie.id}", 1_200_000, MOVIE_MILLIS)
        // How the player marks a title watched: progress at its full length.
        deviceA.graph.viewerLibrary.recordProgress("ep:${deviceA.episode.id}", EPISODE_MILLIS, EPISODE_MILLIS)

        syncAToB()

        assertThat(deviceB.resumeFrom("Alex", "movie:${deviceB.movie.id}")).isEqualTo(1_200_000)
        assertThat(deviceB.statusOf("Alex", deviceB.episode.id)).isEqualTo(WatchStatus.Watched)
        assertThat(deviceB.resumeFrom("Sam", "movie:${deviceB.movie.id}")).isEqualTo(0)
        assertThat(deviceB.statusOf("Sam", deviceB.episode.id)).isEqualTo(WatchStatus.Unwatched)
    }

    @Test
    fun `whichever device played a title more recently decides where it resumes`() = runTest {
        deviceA.seed("Alex")
        deviceB.seed("Alex")
        // B watched the movie last; A watched the episode last.
        deviceA.hadOldProgress("Alex", "movie:${deviceA.movie.id}", positionMillis = 600_000)
        deviceB.asProfile("Alex")
        deviceB.graph.viewerLibrary.recordProgress("movie:${deviceB.movie.id}", 3_000_000, MOVIE_MILLIS)
        deviceB.hadOldProgress("Alex", "ep:${deviceB.episode.id}", positionMillis = 300_000)
        deviceA.asProfile("Alex")
        deviceA.graph.viewerLibrary.recordProgress("ep:${deviceA.episode.id}", 900_000, EPISODE_MILLIS)

        val result = syncAToB()

        assertThat(deviceB.resumeFrom("Alex", "movie:${deviceB.movie.id}")).isEqualTo(3_000_000)
        assertThat(deviceB.resumeFrom("Alex", "ep:${deviceB.episode.id}")).isEqualTo(900_000)
        assertThat(result.positions).isEqualTo(1)
    }

    private companion object {
        const val MOVIE_MILLIS = 6_000_000L
        const val EPISODE_MILLIS = 2_400_000L
    }
}
