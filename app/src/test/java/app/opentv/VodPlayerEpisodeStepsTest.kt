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
import app.opentv.data.repo.MediaKey
import app.opentv.data.repo.WatchStatus
import app.opentv.ui.vod.VodPlayerViewModel
import app.opentv.ui.vod.toNextEpisode
import app.opentv.ui.vod.toPreviousEpisode
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The episode player's Previous and Next episode: which exist, and moving to them.
 */
// Plain Application: OpenTvApp schedules WorkManager on start, which the view model doesn't need.
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class VodPlayerEpisodeStepsTest {

    private val app: Application = ApplicationProvider.getApplicationContext()
    private val graph = ServiceLocator.Graph(app)
    private val playback = FakePlayback()

    @Before
    fun mainDispatcher() = Dispatchers.setMain(UnconfinedTestDispatcher())

    @After
    fun close() {
        graph.database.close()
        Dispatchers.resetMain()
    }

    private data class Steps(val previous: Boolean, val next: Boolean)

    private val VodPlayerViewModel.steps get() = episodeSteps.value.let { Steps(it.previous, it.next) }

    private fun film(id: Long, name: String) = Movie(
        id = id,
        sourceId = 1,
        streamId = "$id",
        name = name,
        categoryId = null,
        posterUrl = null,
        rating = null,
        year = null,
        plot = null,
        durationSeconds = null,
        containerExtension = "mkv",
        streamUrl = "https://example.com/$id",
        tmdbId = "8363",
    )

    @Test
    fun `an episode in the middle has episodes either side, a show's first none before, and its last none after`() =
        runTest {
            graph.database.episodes().upsertAll((1..3).map { testEpisode(1, it) })
            val vm = VodPlayerViewModel(app, graph)

            vm.attach(testEpisode(1, 2).key, playback)
            assertThat(vm.steps).isEqualTo(Steps(previous = true, next = true))

            vm.attach(testEpisode(1, 1).key, playback)
            assertThat(vm.steps).isEqualTo(Steps(previous = false, next = true))

            vm.attach(testEpisode(1, 3).key, playback)
            assertThat(vm.steps).isEqualTo(Steps(previous = true, next = false))
        }

    @Test
    fun `a film has no episodes either side`() = runTest {
        graph.database.movies().upsertAll(listOf(film(1, "Film")))
        val vm = VodPlayerViewModel(app, graph)

        vm.attach(MediaKey.Movie(1), playback)

        assertThat(vm.steps).isEqualTo(Steps(previous = false, next = false))
    }

    @Test
    fun `Next episode plays the following one from its resume point, leaving this one part-watched, not Watched`() =
        runTest {
            graph.database.episodes().upsertAll((1..3).map { testEpisode(1, it) })
            graph.viewerLibrary.recordProgress("ep:12", positionMillis = 600_000, durationMillis = EPISODE_MILLIS)
            val vm = VodPlayerViewModel(app, graph)
            vm.attach(testEpisode(1, 1).key, playback)
            playback.positionMillis = 300_000

            vm.toNextEpisode()

            assertThat(playback.played.last()).isEqualTo(testEpisode(1, 2).key to 600_000L)
            assertThat(vm.current.value).isEqualTo(testEpisode(1, 2).key)
            assertThat(graph.viewerLibrary.resumeFrom("ep:11")).isEqualTo(300_000L)
            assertThat(graph.viewerLibrary.episodeStatuses().first()[11]).isNotEqualTo(WatchStatus.Watched)
        }

    @Test
    fun `Previous episode starts a Watched episode from the start, and the episodes either side follow it`() = runTest {
        graph.database.episodes().upsertAll((1..3).map { testEpisode(1, it) })
        graph.viewerLibrary.recordProgress("ep:11", EPISODE_MILLIS, EPISODE_MILLIS)
        val vm = VodPlayerViewModel(app, graph)
        vm.attach(testEpisode(1, 2).key, playback)

        vm.toPreviousEpisode()

        assertThat(playback.played.last()).isEqualTo(testEpisode(1, 1).key to 0L)
        assertThat(vm.steps).isEqualTo(Steps(previous = false, next = true))
    }

    @Test
    fun `Previous episode on a show's first episode does nothing`() = runTest {
        graph.database.episodes().upsertAll((1..3).map { testEpisode(1, it) })
        val vm = VodPlayerViewModel(app, graph)
        vm.attach(testEpisode(1, 1).key, playback)

        vm.toPreviousEpisode()

        assertThat(playback.played).containsExactly(testEpisode(1, 1).key to 0L)
    }

    @Test
    fun `two quick presses of Next move on two episodes, starting each once`() = runTest {
        graph.database.episodes().upsertAll((1..3).map { testEpisode(1, it) })
        val vm = VodPlayerViewModel(app, graph)
        vm.attach(testEpisode(1, 1).key, playback)

        listOf(launch { vm.toNextEpisode() }, launch { vm.toNextEpisode() }).joinAll()

        assertThat(playback.played.map { it.first })
            .containsExactly(testEpisode(1, 1).key, testEpisode(1, 2).key, testEpisode(1, 3).key)
            .inOrder()
    }

    @Test
    fun `episodes either side that turn up as the series loads beside playback reach the player`() = runTest {
        val panel = FakePanel()
        val fetching = ServiceLocator.Graph(app, http = panel.http)
        try {
            fetching.database.sources().insert(PANEL_SOURCE)
            fetching.database.episodes().upsertAll(listOf(testEpisode(1, 2)))
            panel.replies["get_series_info:s"] = SERIES_OF_THREE
            val hold = panel.hold("get_series_info:s")
            val vm = VodPlayerViewModel(app, fetching)

            vm.attach(testEpisode(1, 2).key, playback)
            during(hold) { assertThat(vm.steps).isEqualTo(Steps(previous = false, next = false)) }
            hold.release()

            withContext(Dispatchers.Default) { withTimeout(WAIT_MILLIS) { vm.episodeSteps.first { it.next } } }
            assertThat(vm.steps).isEqualTo(Steps(previous = true, next = true))
        } finally {
            fetching.database.close()
        }
    }

    @Test
    fun `a last episode keeps its Previous episode when loading the rest of its series finds nothing`() = runTest {
        // No source stored, so there's no provider to ask: the fetch finds nothing.
        graph.database.episodes().upsertAll((1..3).map { testEpisode(1, it) })
        val vm = VodPlayerViewModel(app, graph)

        vm.attach(testEpisode(1, 3).key, playback)
        vm.toPreviousEpisode()

        assertThat(playback.played.last()).isEqualTo(testEpisode(1, 2).key to 0L)
    }

    private companion object {
        const val WAIT_MILLIS = UI_WAIT_MILLIS
    }
}
