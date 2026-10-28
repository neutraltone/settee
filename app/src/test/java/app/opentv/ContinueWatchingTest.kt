/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import app.opentv.data.db.OpenTvDatabase
import app.opentv.data.model.Episode
import app.opentv.data.repo.ContinueWatchingCard
import app.opentv.data.repo.ViewerLibrary
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

// Plain Application: OpenTvApp schedules WorkManager on start, which the database doesn't need.
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class ContinueWatchingTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val db = Room.inMemoryDatabaseBuilder(context, OpenTvDatabase::class.java)
        .allowMainThreadQueries()
        .build()
    private val activeProfile = MutableStateFlow(1L)
    private var now = 1_000_000L
    private val library = ViewerLibrary(db.positions(), db.episodes(), activeProfile, clock = { now })

    @After
    fun close() = db.close()

    /** Two seasons of three. */
    private val episodes = listOf(1, 2).flatMap { season ->
        (1..3).map { number -> testEpisode(season, number) }
    }

    private suspend fun seriesCards() = library.continueWatchingSeries().first()

    private suspend fun stopPartWay(ep: Episode) =
        library.recordProgress("ep:${ep.id}", positionMillis = 600_000, durationMillis = 2_400_000)

    @Test
    fun `an episode stopped part way gives its series a card showing how far through it is`() = runTest {
        db.episodes().upsertAll(episodes)
        stopPartWay(testEpisode(1, 2))

        assertThat(seriesCards()).containsExactly(ContinueWatchingCard.Series(testEpisode(1, 2), progress = 0.25f))
    }

    private suspend fun watch(ep: Episode) =
        library.recordProgress("ep:${ep.id}", positionMillis = 2_400_000, durationMillis = 2_400_000)

    @Test
    fun `finishing an episode puts the series' Up Next in Continue Watching with nothing played of it yet`() = runTest {
        db.episodes().upsertAll(episodes)
        watch(testEpisode(1, 3))

        assertThat(seriesCards()).containsExactly(ContinueWatchingCard.Series(testEpisode(2, 1), progress = 0f))
    }

    @Test
    fun `finishing the last available episode takes the series out of Continue Watching`() = runTest {
        db.episodes().upsertAll(episodes)
        watch(testEpisode(2, 3))

        assertThat(seriesCards()).isEmpty()
    }

    @Test
    fun `a series has one card however many of its episodes were played`() = runTest {
        db.episodes().upsertAll(episodes)
        stopPartWay(testEpisode(1, 1))
        now += 60_000
        watch(testEpisode(1, 2))
        now += 60_000
        stopPartWay(testEpisode(2, 1))

        assertThat(seriesCards()).containsExactly(ContinueWatchingCard.Series(testEpisode(2, 1), progress = 0.25f))
    }

    private val otherSeries = testEpisode(1, 1).copy(
        id = 101,
        seriesId = "other",
        episodeId = "other-1-1",
        streamUrl = "https://example.com/o.mkv",
    )

    @Test
    fun `cards are ordered by when the Profile last played them, newest first`() = runTest {
        db.episodes().upsertAll(episodes + otherSeries)
        stopPartWay(testEpisode(1, 1))
        now += 60_000
        stopPartWay(otherSeries)
        now += 60_000
        watch(testEpisode(1, 1))

        assertThat(seriesCards()).containsExactly(
            ContinueWatchingCard.Series(testEpisode(1, 2), progress = 0f),
            ContinueWatchingCard.Series(otherSeries, progress = 0.25f),
        ).inOrder()
    }

    @Test
    fun `movies' Continue Watching has only movies and series' only series`() = runTest {
        db.episodes().upsertAll(episodes)
        library.recordProgress("movie:3", positionMillis = 600_000, durationMillis = 2_400_000)
        now += 60_000
        stopPartWay(testEpisode(1, 1))

        assertThat(library.continueWatchingMovies().first())
            .containsExactly(ContinueWatchingCard.Movie(movieId = 3, progress = 0.25f))
        assertThat(seriesCards()).containsExactly(ContinueWatchingCard.Series(testEpisode(1, 1), progress = 0.25f))
    }
}
