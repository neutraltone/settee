/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv

import app.opentv.data.model.Movie
import app.opentv.data.repo.MediaKey
import app.opentv.data.repo.WatchStatus
import app.opentv.ui.vod.VodPlayerViewModel
import app.opentv.ui.vod.toNextEpisode
import app.opentv.ui.vod.toPreviousEpisode
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The episode player's Previous and Next episode: which exist, and moving to them.
 */
// Plain Application: OpenTvApp schedules WorkManager on start, which the view model doesn't need.
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class VodPlayerEpisodeStepsTest {

    // Only asked for a series when a provider is stored, which only the test of loading one does.
    private val panel = FakePanel()

    @get:Rule
    val testGraph = TestGraph(http = panel.http)

    private val app = testGraph.app
    private val graph = testGraph.graph
    private val playback = FakePlayback()

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

    /** Runs [step] as the player screen does, on the main thread, and settles what it set off. */
    private fun VodPlayerViewModel.settled(step: suspend VodPlayerViewModel.() -> Unit) {
        testGraph.launch { step() }
        testGraph.awaitIdle()
    }

    private fun VodPlayerViewModel.attachTo(key: MediaKey) = settled { attach(key, playback) }

    private fun playerWith(vararg episodes: Int): VodPlayerViewModel {
        runBlocking { graph.database.episodes().upsertAll(episodes.map { testEpisode(1, it) }) }
        return testGraph.track(VodPlayerViewModel(app, graph))
    }

    @Test
    fun `an episode in the middle has episodes either side, a show's first none before, and its last none after`() {
        val vm = playerWith(1, 2, 3)

        vm.attachTo(testEpisode(1, 2).key)
        assertThat(vm.steps).isEqualTo(Steps(previous = true, next = true))

        vm.attachTo(testEpisode(1, 1).key)
        assertThat(vm.steps).isEqualTo(Steps(previous = false, next = true))

        vm.attachTo(testEpisode(1, 3).key)
        assertThat(vm.steps).isEqualTo(Steps(previous = true, next = false))
    }

    @Test
    fun `a film has no episodes either side`() {
        runBlocking { graph.database.movies().upsertAll(listOf(film(1, "Film"))) }
        val vm = testGraph.track(VodPlayerViewModel(app, graph))

        vm.attachTo(MediaKey.Movie(1))

        assertThat(vm.steps).isEqualTo(Steps(previous = false, next = false))
    }

    @Test
    fun `Next episode plays the following one from its resume point, leaving this one part-watched, not Watched`() {
        val vm = playerWith(1, 2, 3)
        runBlocking {
            graph.viewerLibrary.recordProgress("ep:12", positionMillis = 600_000, durationMillis = EPISODE_MILLIS)
        }
        vm.attachTo(testEpisode(1, 1).key)
        playback.positionMillis = 300_000

        vm.settled { toNextEpisode() }

        assertThat(playback.played.last()).isEqualTo(testEpisode(1, 2).key to 600_000L)
        assertThat(vm.current.value).isEqualTo(testEpisode(1, 2).key)
        assertThat(runBlocking { graph.viewerLibrary.resumeFrom("ep:11") }).isEqualTo(300_000L)
        assertThat(runBlocking { graph.viewerLibrary.episodeStatuses().first()[11] })
            .isNotEqualTo(WatchStatus.Watched)
    }

    @Test
    fun `Previous episode starts a Watched episode from the start, and the episodes either side follow it`() {
        val vm = playerWith(1, 2, 3)
        runBlocking { graph.viewerLibrary.recordProgress("ep:11", EPISODE_MILLIS, EPISODE_MILLIS) }
        vm.attachTo(testEpisode(1, 2).key)

        vm.settled { toPreviousEpisode() }

        assertThat(playback.played.last()).isEqualTo(testEpisode(1, 1).key to 0L)
        assertThat(vm.steps).isEqualTo(Steps(previous = false, next = true))
    }

    @Test
    fun `Previous episode on a show's first episode does nothing`() {
        val vm = playerWith(1, 2, 3)
        vm.attachTo(testEpisode(1, 1).key)

        vm.settled { toPreviousEpisode() }

        assertThat(playback.played).containsExactly(testEpisode(1, 1).key to 0L)
    }

    @Test
    fun `two quick presses of Next move on two episodes, starting each once`() {
        val vm = playerWith(1, 2, 3)
        vm.attachTo(testEpisode(1, 1).key)

        // The second press comes while the first is still on its way.
        testGraph.launch { vm.toNextEpisode() }
        testGraph.launch { vm.toNextEpisode() }
        testGraph.awaitIdle()

        assertThat(playback.played.map { it.first })
            .containsExactly(testEpisode(1, 1).key, testEpisode(1, 2).key, testEpisode(1, 3).key)
            .inOrder()
    }

    @Test
    fun `episodes either side that turn up as the series loads beside playback reach the player`() {
        runBlocking {
            graph.database.sources().insert(PANEL_SOURCE)
            graph.database.episodes().upsertAll(listOf(testEpisode(1, 2)))
        }
        panel.replies["get_series_info:s"] = SERIES_OF_THREE
        val hold = panel.hold("get_series_info:s")
        val vm = testGraph.track(VodPlayerViewModel(app, graph))

        vm.attachTo(testEpisode(1, 2).key)
        assertThat(hold.reached.count).isEqualTo(0L)
        assertThat(vm.steps).isEqualTo(Steps(previous = false, next = false))
        hold.release()
        testGraph.awaitIdle()

        assertThat(vm.steps).isEqualTo(Steps(previous = true, next = true))
    }

    @Test
    fun `a last episode keeps its Previous episode when loading the rest of its series finds nothing`() {
        // No source stored, so there's no provider to ask: the fetch finds nothing.
        val vm = playerWith(1, 2, 3)

        vm.attachTo(testEpisode(1, 3).key)
        vm.settled { toPreviousEpisode() }

        assertThat(playback.played.last()).isEqualTo(testEpisode(1, 2).key to 0L)
    }
}
