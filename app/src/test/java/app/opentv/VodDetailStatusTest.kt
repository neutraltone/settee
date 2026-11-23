/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv

import app.opentv.data.model.Movie
import app.opentv.data.model.Series
import app.opentv.data.repo.CoverStatus
import app.opentv.ui.VodViewModel
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** What the detail pages' heroes say about where the active Profile is with their title. */
// Plain Application: OpenTvApp schedules WorkManager on start, which the view model doesn't need.
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class VodDetailStatusTest {

    @get:Rule
    val testGraph = TestGraph()

    private val app = testGraph.app
    private val graph = testGraph.graph

    private fun viewModel() = testGraph.track(VodViewModel(app, graph))

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
    fun `a show part way through an episode says which and how long is left`() = runBlocking {
        withEpisodes()
        graph.viewerLibrary.recordProgress("ep:22", positionMillis = 600_000, durationMillis = 2_400_000)

        val status = testGraph.firstWhenIdle(viewModel().seriesStatus(episodes)) { it != null }

        assertThat(status).isEqualTo(
            CoverStatus.InProgress(progress = 0.25f, remainingMillis = 1_800_000, episode = testEpisode(2, 2)),
        )
    }

    @Test
    fun `a show watched to the end says Watched`() = runBlocking {
        withEpisodes()
        graph.viewerLibrary.catchUp.markSeasonWatched((1..2).flatMap { s -> (1..3).map { testEpisode(s, it) } })

        val status = testGraph.firstWhenIdle(viewModel().seriesStatus(episodes)) { it != null }

        assertThat(status).isEqualTo(CoverStatus.Watched)
    }

    @Test
    fun `a show never started says nothing`() = runBlocking {
        withEpisodes()

        assertThat(testGraph.firstWhenIdle(viewModel().seriesStatus(episodes))).isNull()
    }

    @Test
    fun `a film part way through says how long is left`() = runBlocking {
        graph.database.movies().upsertAll(listOf(film))
        graph.viewerLibrary.recordProgress("movie:7", positionMillis = 3_000_000, durationMillis = 6_000_000)

        val status = testGraph.firstWhenIdle(viewModel().movieStatus(film.id)) { it != null }

        assertThat(status).isEqualTo(CoverStatus.InProgress(progress = 0.5f, remainingMillis = 3_000_000))
    }

    @Test
    fun `another film's progress says nothing about this one`() = runBlocking {
        // Named apart: one of the same name is a Version of this film, whose progress is this film's too.
        graph.database.movies().upsertAll(listOf(film, film.copy(id = 70, streamId = "m70", name = "Another Film")))
        graph.viewerLibrary.recordProgress("movie:70", positionMillis = 3_000_000, durationMillis = 6_000_000)

        assertThat(testGraph.firstWhenIdle(viewModel().movieStatus(film.id))).isNull()
    }

    @Test
    fun `a film never started says nothing`() = runBlocking {
        graph.database.movies().upsertAll(listOf(film))

        assertThat(testGraph.firstWhenIdle(viewModel().movieStatus(film.id))).isNull()
    }
}
