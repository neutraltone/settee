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

    /**
     * One device: its own database, holding the same provider's catalogue under its own row ids.
     * Settings are process-wide, so the devices share an active Profile: every helper picks its
     * Profile before acting rather than trusting whichever the other device left selected.
     */
    private inner class Device(rowIdOffset: Long) {
        val db: OpenTvDatabase = Room.inMemoryDatabaseBuilder(app, OpenTvDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        val graph = ServiceLocator.Graph(app) { db }
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
                streamUrl = "https://example.com/live/c$n.ts",
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
                streamUrl = "https://example.com/movie/m$n.mkv",
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
        val episode = testEpisode(1, 1).copy(id = 11 + rowIdOffset, sourceId = sourceId)
        val movieKey = "movie:${movies[0].id}"
        val episodeKey = "ep:${episode.id}"

        suspend fun seed(vararg profiles: String) {
            db.sources().insert(
                Source(id = sourceId, name = "Provider", kind = SourceKind.M3U, url = "https://example.com"),
            )
            db.channels().upsertAll(channels)
            db.movies().upsertAll(movies)
            db.series().upsertAll(series)
            db.episodes().upsertAll(listOf(episode))
            profiles.forEach { db.profiles().insert(Profile(name = it)) }
        }

        suspend fun hasProfile(name: String): Boolean = db.profiles().byName(name) != null

        suspend fun profileId(name: String): Long = checkNotNull(db.profiles().byName(name)).id

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

    @After
    fun close() {
        deviceA.db.close()
        deviceB.db.close()
        // Settings outlive the test and both devices share them; leave the default Profile active.
        deviceA.graph.settings.setActiveProfile(1L)
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

    private companion object {
        const val MOVIE_MILLIS = 6_000_000L
        const val EPISODE_MILLIS = 2_400_000L
    }
}
