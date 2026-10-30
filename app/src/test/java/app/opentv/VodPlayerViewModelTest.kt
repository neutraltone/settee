/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import app.opentv.core.ServiceLocator
import app.opentv.data.model.Series
import app.opentv.data.repo.MediaKey
import app.opentv.data.repo.WatchStatus
import app.opentv.player.NextEpisodeCountdown.Overlay
import app.opentv.ui.vod.VodPlayerViewModel
import app.opentv.ui.vod.VodPlayerViewModel.NextEpisodeCard
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

// Plain Application: OpenTvApp schedules WorkManager on start, which the view model doesn't need.
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class VodPlayerViewModelTest {

    private val app: Application = ApplicationProvider.getApplicationContext()
    private val graph = ServiceLocator.Graph(app)

    @Before
    fun mainDispatcher() = Dispatchers.setMain(UnconfinedTestDispatcher())

    @After
    fun close() {
        graph.settings.setPlayNextAutomatically(true)
        graph.database.close()
        Dispatchers.resetMain()
    }

    private val playback = FakePlayback()

    private suspend fun VodPlayerViewModel.playToEnd() = playToEnd(playback)

    @Test
    fun `when the countdown runs out the Next Episode plays in the same player from its own resume point`() = runTest {
        graph.database.episodes().upsertAll((1..3).map { testEpisode(1, it) })
        graph.viewerLibrary.recordProgress("ep:12", positionMillis = 600_000, durationMillis = EPISODE_MILLIS)
        val vm = VodPlayerViewModel(app, graph)

        vm.attach(testEpisode(1, 1).key, playback)
        vm.playToEnd()

        assertThat(playback.played).containsExactly(
            testEpisode(1, 1).key to 0L,
            testEpisode(1, 2).key to 600_000L,
        ).inOrder()
        assertThat(vm.current.value).isEqualTo(MediaKey.Episode(12))
    }

    @Test
    fun `OK on the card moves on and makes even a short episode Watched`() = runTest {
        graph.database.episodes().upsertAll((1..3).map { testEpisode(1, it) })
        val vm = VodPlayerViewModel(app, graph)
        vm.attach(testEpisode(1, 1).key, playback)
        // Three minutes long, the card up with 10 seconds left: under 93% through.
        playback.durationMillis = 180_000
        playback.positionMillis = 170_000
        vm.poll()

        vm.ok()

        assertThat(vm.current.value).isEqualTo(MediaKey.Episode(12))
        assertThat(graph.viewerLibrary.episodeStatuses().first()[11]).isEqualTo(WatchStatus.Watched)
    }

    @Test
    fun `a rebuilt player screen carries on with the Next Episode, not the one in the route`() = runTest {
        graph.database.episodes().upsertAll((1..3).map { testEpisode(1, it) })
        val vm = VodPlayerViewModel(app, graph)
        val route = testEpisode(1, 1).key
        vm.attach(route, playback)
        vm.playToEnd()
        playback.positionMillis = 300_000
        vm.saveProgress()

        val rebuilt = FakePlayback()
        vm.attach(route, rebuilt)

        assertThat(rebuilt.played).containsExactly(testEpisode(1, 2).key to 300_000L)
    }

    @Test
    fun `after three unattended episodes it pauses to ask, stops the stream at 5 minutes, and Continue plays on`() =
        runTest {
            graph.database.episodes().upsertAll((1..6).map { testEpisode(1, it) })
            var now = 0L
            val vm = VodPlayerViewModel(app, graph, clock = { now })
            vm.attach(testEpisode(1, 1).key, playback)
            repeat(3) { vm.playToEnd() }

            vm.playToEnd()
            assertThat(playback.paused).isTrue()
            assertThat(vm.overlay.value).isEqualTo(Overlay.StillWatching(streamStopped = false))

            now += 5 * 60_000
            vm.poll()
            assertThat(playback.stopped).isTrue()

            vm.continueWatching()
            assertThat(vm.current.value).isEqualTo(MediaKey.Episode(15))
            assertThat(vm.overlay.value).isEqualTo(Overlay.None)
        }

    @Test
    fun `with Play next episode automatically off the card waits for OK`() = runTest {
        graph.database.episodes().upsertAll((1..3).map { testEpisode(1, it) })
        graph.settings.setPlayNextAutomatically(false)
        val vm = VodPlayerViewModel(app, graph)
        vm.attach(testEpisode(1, 1).key, playback)

        vm.playToEnd()

        assertThat(vm.current.value).isEqualTo(MediaKey.Episode(11))
        assertThat(vm.overlay.value).isEqualTo(Overlay.Card(secondsLeft = 0, counting = false))
    }

    @Test
    fun `the card shows the Next Episode's thumbnail, or else the series artwork`() = runTest {
        graph.database.series().upsertAll(listOf(show))
        graph.database.episodes().upsertAll(
            listOf(
                testEpisode(1, 1),
                testEpisode(1, 2).copy(stillUrl = "https://example.com/still.jpg"),
                testEpisode(1, 3),
            ),
        )
        val vm = VodPlayerViewModel(app, graph)

        vm.attach(testEpisode(1, 1).key, playback)
        assertThat(vm.nextEpisode.value).isEqualTo(
            NextEpisodeCard(season = 1, episode = 2, title = "Episode 2", imageUrl = "https://example.com/still.jpg"),
        )

        vm.playToEnd()
        assertThat(vm.nextEpisode.value?.imageUrl).isEqualTo("https://example.com/backdrop.jpg")
    }

    @Test
    fun `a movie gets no card and plays to its end`() = runTest {
        val vm = VodPlayerViewModel(app, graph)

        vm.attach(MediaKey.Movie(7), playback)
        playback.positionMillis = EPISODE_MILLIS - 10_000
        vm.poll()

        assertThat(vm.overlay.value).isEqualTo(Overlay.None)
        assertThat(vm.nextEpisode.value).isNull()
    }

    @Test
    fun `a player opened again with something new plays the new thing`() = runTest {
        graph.database.episodes().upsertAll((1..3).map { testEpisode(1, it) })
        val vm = VodPlayerViewModel(app, graph)
        vm.attach(testEpisode(1, 1).key, playback)
        vm.playToEnd()

        val recording = MediaKey.Recording(3)
        vm.attach(recording, playback)

        assertThat(vm.current.value).isEqualTo(recording)
        assertThat(playback.played.last()).isEqualTo(recording to 0L)
    }

    @Test
    fun `a player rebuilt while asking whether anyone is still watching keeps asking`() = runTest {
        graph.database.episodes().upsertAll((1..6).map { testEpisode(1, it) })
        val vm = VodPlayerViewModel(app, graph)
        val route = testEpisode(1, 1).key
        vm.attach(route, playback)
        repeat(4) { vm.playToEnd() }

        val rebuilt = FakePlayback()
        vm.attach(route, rebuilt)

        assertThat(rebuilt.played).isEmpty()
        assertThat(vm.overlay.value).isEqualTo(Overlay.StillWatching(streamStopped = false))
    }

    @Test
    fun `Continue after the stream was stopped still makes the finished episode Watched`() = runTest {
        graph.database.episodes().upsertAll((1..6).map { testEpisode(1, it) })
        var now = 0L
        val vm = VodPlayerViewModel(app, graph, clock = { now })
        vm.attach(testEpisode(1, 1).key, playback)
        repeat(4) { vm.playToEnd() }
        now += 5 * 60_000
        vm.poll()
        // A stopped player has let go of its media and no longer knows how long it was.
        playback.durationMillis = 0
        playback.positionMillis = 0

        vm.continueWatching()

        assertThat(graph.viewerLibrary.episodeStatuses().first()[14]).isEqualTo(WatchStatus.Watched)
    }

    @Test
    fun `a rebuilt player resumes where the last save saw it, without waiting for the write`() = runTest {
        graph.database.episodes().upsertAll((1..3).map { testEpisode(1, it) })
        val vm = VodPlayerViewModel(app, graph)
        val route = testEpisode(1, 1).key
        vm.attach(route, playback)
        playback.positionMillis = 900_000
        vm.poll()

        val rebuilt = FakePlayback()
        vm.attach(route, rebuilt)

        assertThat(rebuilt.played).containsExactly(testEpisode(1, 1).key to 900_000L)
    }

    private val show = Series(
        sourceId = 1,
        seriesId = "s",
        name = "The Show",
        categoryId = null,
        posterUrl = "https://example.com/poster.jpg",
        rating = null,
        year = null,
        plot = null,
        backdropUrl = "https://example.com/backdrop.jpg",
    )
}
