/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import app.opentv.core.ServiceLocator
import app.opentv.data.model.Movie
import app.opentv.data.model.Series
import app.opentv.data.repo.CoverStatus
import app.opentv.ui.VodViewModel
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** What the detail pages' heroes say about where the active Profile is with their title. */
// Plain Application: OpenTvApp schedules WorkManager on start, which the view model doesn't need.
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class VodDetailStatusTest {

    private val app: Application = ApplicationProvider.getApplicationContext()
    private val graph = ServiceLocator.Graph(app)

    @Before
    fun mainDispatcher() = Dispatchers.setMain(UnconfinedTestDispatcher())

    @After
    fun close() {
        graph.database.close()
        Dispatchers.resetMain()
    }

    private val show = Series(
        sourceId = 1,
        seriesId = "s",
        name = "The Show",
        categoryId = null,
        posterUrl = null,
        rating = null,
        year = null,
        plot = null,
    )

    private val film = Movie(
        id = 7,
        sourceId = 1,
        streamId = "m",
        name = "The Film",
        categoryId = null,
        posterUrl = null,
        rating = null,
        year = null,
        plot = null,
        durationSeconds = null,
        containerExtension = null,
        streamUrl = "https://example.com/m.mkv",
    )

    private val episodes = (1..2).flatMap { s -> (1..3).map { testEpisode(s, it) } }

    private suspend fun withEpisodes() {
        graph.database.series().upsertAll(listOf(show))
        graph.database.episodes().upsertAll(episodes)
    }

    @Test
    fun `a show part way through an episode says which and how long is left`() = runTest {
        withEpisodes()
        graph.viewerLibrary.recordProgress("ep:22", positionMillis = 600_000, durationMillis = 2_400_000)

        val status = VodViewModel(app, graph).seriesStatus(episodes).first { it != null }

        assertThat(status).isEqualTo(
            CoverStatus.InProgress(progress = 0.25f, remainingMillis = 1_800_000, episode = testEpisode(2, 2)),
        )
    }

    @Test
    fun `a show watched to the end says Watched`() = runTest {
        withEpisodes()
        graph.viewerLibrary.catchUp.markSeasonWatched((1..2).flatMap { s -> (1..3).map { testEpisode(s, it) } })

        val status = VodViewModel(app, graph).seriesStatus(episodes).first { it != null }

        assertThat(status).isEqualTo(CoverStatus.Watched)
    }

    @Test
    fun `a show never started says nothing`() = runTest {
        withEpisodes()

        assertThat(VodViewModel(app, graph).seriesStatus(episodes).first()).isNull()
    }

    @Test
    fun `a film part way through says how long is left`() = runTest {
        graph.database.movies().upsertAll(listOf(film))
        graph.viewerLibrary.recordProgress("movie:7", positionMillis = 3_000_000, durationMillis = 6_000_000)

        val status = VodViewModel(app, graph).movieStatus(film.id).first { it != null }

        assertThat(status).isEqualTo(CoverStatus.InProgress(progress = 0.5f, remainingMillis = 3_000_000))
    }

    @Test
    fun `another film's progress says nothing about this one`() = runTest {
        graph.database.movies().upsertAll(listOf(film, film.copy(id = 70, streamId = "m70")))
        graph.viewerLibrary.recordProgress("movie:70", positionMillis = 3_000_000, durationMillis = 6_000_000)

        assertThat(VodViewModel(app, graph).movieStatus(film.id).first()).isNull()
    }

    @Test
    fun `a film never started says nothing`() = runTest {
        graph.database.movies().upsertAll(listOf(film))

        assertThat(VodViewModel(app, graph).movieStatus(film.id).first()).isNull()
    }
}
