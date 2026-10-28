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
import app.opentv.data.repo.SeriesMainAction
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
class UpNextTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val db = Room.inMemoryDatabaseBuilder(context, OpenTvDatabase::class.java)
        .allowMainThreadQueries()
        .build()
    private val activeProfile = MutableStateFlow(1L)
    private var now = 1_000_000L
    private val library = ViewerLibrary(db.positions(), db.favourites(), activeProfile, clock = { now })

    @After
    fun close() = db.close()

    /** Two seasons of three. */
    private val episodes = listOf(1, 2).flatMap { season ->
        (1..3).map { number -> testEpisode(season, number) }
    }

    private suspend fun mainAction(eps: List<Episode> = episodes) = library.seriesMainAction(eps).first()

    @Test
    fun `with nothing watched the main action plays the first episode`() = runTest {
        assertThat(mainAction()).isEqualTo(SeriesMainAction.Play(testEpisode(1, 1)))
    }

    private suspend fun watch(ep: Episode) =
        library.recordProgress("ep:${ep.id}", positionMillis = 2_400_000, durationMillis = 2_400_000)

    @Test
    fun `after an episode is Watched the main action plays the next one in its season`() = runTest {
        watch(testEpisode(1, 2))

        assertThat(mainAction()).isEqualTo(SeriesMainAction.Play(testEpisode(1, 3)))
    }

    @Test
    fun `an episode stopped part way is resumed`() = runTest {
        watch(testEpisode(1, 1))
        now += 60_000
        library.recordProgress("ep:${testEpisode(1, 2).id}", positionMillis = 600_000, durationMillis = 2_400_000)

        assertThat(mainAction()).isEqualTo(SeriesMainAction.Resume(testEpisode(1, 2)))
    }

    @Test
    fun `after a season finale Up Next is the first episode of the next season`() = runTest {
        watch(testEpisode(1, 3))

        assertThat(mainAction()).isEqualTo(SeriesMainAction.Play(testEpisode(2, 1)))
    }

    @Test
    fun `with no Up Next after the last episode the main action plays the first one`() = runTest {
        episodes.forEach {
            now += 60_000
            watch(it)
        }

        assertThat(mainAction()).isEqualTo(SeriesMainAction.Play(testEpisode(1, 1)))
    }

    @Test
    fun `after skipping ahead Up Next follows the episode watched most recently`() = runTest {
        watch(testEpisode(1, 1))
        now += 60_000
        watch(testEpisode(2, 2))

        assertThat(mainAction()).isEqualTo(SeriesMainAction.Play(testEpisode(2, 3)))
    }

    @Test
    fun `after going back to an earlier episode Up Next follows that one`() = runTest {
        watch(testEpisode(2, 2))
        now += 60_000
        watch(testEpisode(1, 1))

        assertThat(mainAction()).isEqualTo(SeriesMainAction.Play(testEpisode(1, 2)))
    }

    @Test
    fun `an episode abandoned before a more recently Watched one is not resumed`() = runTest {
        library.recordProgress("ep:${testEpisode(1, 1).id}", positionMillis = 600_000, durationMillis = 2_400_000)
        now += 60_000
        watch(testEpisode(1, 2))

        assertThat(mainAction()).isEqualTo(SeriesMainAction.Play(testEpisode(1, 3)))
    }

    @Test
    fun `another Profile's viewing does not move this Profile's Up Next`() = runTest {
        activeProfile.value = 2L
        watch(testEpisode(1, 2))
        activeProfile.value = 1L

        assertThat(mainAction()).isEqualTo(SeriesMainAction.Play(testEpisode(1, 1)))
    }

    @Test
    fun `episodes of other series do not count`() = runTest {
        library.recordProgress("ep:999", positionMillis = 2_400_000, durationMillis = 2_400_000)

        assertThat(mainAction()).isEqualTo(SeriesMainAction.Play(testEpisode(1, 1)))
    }

    @Test
    fun `a series with no episodes yet has no main action`() = runTest {
        assertThat(mainAction(emptyList())).isNull()
    }

    private val withSpecial = listOf(testEpisode(0, 1)) + episodes

    @Test
    fun `with nothing watched the main action skips the specials and plays S1 E1`() = runTest {
        assertThat(mainAction(withSpecial)).isEqualTo(SeriesMainAction.Play(testEpisode(1, 1)))
    }

    @Test
    fun `after the last episode the main action goes back to S1 E1, not the specials`() = runTest {
        watch(testEpisode(2, 3))

        assertThat(mainAction(withSpecial)).isEqualTo(SeriesMainAction.Play(testEpisode(1, 1)))
    }

    @Test
    fun `after watching a special Up Next is the episode that follows it`() = runTest {
        watch(testEpisode(0, 1))

        assertThat(mainAction(withSpecial)).isEqualTo(SeriesMainAction.Play(testEpisode(1, 1)))
    }

    @Test
    fun `a series of nothing but specials starts with the first one`() = runTest {
        assertThat(
            mainAction(listOf(testEpisode(0, 1), testEpisode(0, 2))),
        ).isEqualTo(SeriesMainAction.Play(testEpisode(0, 1)))
    }
}
