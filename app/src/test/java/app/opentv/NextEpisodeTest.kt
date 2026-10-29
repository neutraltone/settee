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
import app.opentv.data.repo.ViewerLibrary
import app.opentv.data.repo.WatchStatus
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
class NextEpisodeTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val db = Room.inMemoryDatabaseBuilder(context, OpenTvDatabase::class.java)
        .allowMainThreadQueries()
        .build()
    private val library = ViewerLibrary(db.positions(), db.episodes(), db.favourites(), MutableStateFlow(1L))

    @After
    fun close() = db.close()

    /** Two seasons of five, stored in no particular order. */
    private suspend fun storeSeries(): List<Episode> {
        val episodes = listOf(2, 1).flatMap { season -> (5 downTo 1).map { testEpisode(season, it) } }
        db.episodes().upsertAll(episodes)
        return episodes
    }

    private suspend fun watch(ep: Episode) =
        library.recordProgress("ep:${ep.id}", positionMillis = 2_400_000, durationMillis = 2_400_000)

    @Test
    fun `the Next Episode follows the episode playing, not the last one Watched`() = runTest {
        storeSeries()
        watch(testEpisode(1, 5))

        assertThat(library.nextEpisode(testEpisode(1, 2))).isEqualTo(testEpisode(1, 3))
    }

    @Test
    fun `after a season finale the Next Episode opens the next season`() = runTest {
        storeSeries()

        assertThat(library.nextEpisode(testEpisode(1, 5))).isEqualTo(testEpisode(2, 1))
    }

    @Test
    fun `a series' last episode has no Next Episode`() = runTest {
        storeSeries()

        assertThat(library.nextEpisode(testEpisode(2, 5))).isNull()
    }

    @Test
    fun `moving on from an episode makes it Watched even short of 93 percent`() = runTest {
        storeSeries()
        val short = testEpisode(1, 1)
        // A 3-minute episode with the card up: 15 seconds left is under 93% through.
        library.recordProgress("ep:${short.id}", positionMillis = 165_000, durationMillis = 180_000)

        library.recordMovedOn("ep:${short.id}", durationMillis = 180_000)

        assertThat(library.episodeStatuses().first()[short.id]).isEqualTo(WatchStatus.Watched)
    }
}
