/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv

import android.app.Application
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ApplicationProvider
import app.opentv.core.ServiceLocator
import app.opentv.data.model.Movie
import app.opentv.data.model.Series
import app.opentv.data.model.Source
import app.opentv.data.model.SourceKind
import app.opentv.data.repo.MediaKey
import app.opentv.data.repo.WatchStatus
import app.opentv.player.NextEpisodeCountdown.Overlay
import app.opentv.ui.vod.VodPlayerViewModel
import app.opentv.ui.vod.VodPlayerViewModel.NextEpisodeCard
import app.opentv.ui.vod.chooseVersion
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
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
    fun `a film opened on a Quality this device can't play plays the copy it can, from that copy's own point`() =
        runTest {
            // Robolectric lists no decoders, so here as on a 1080p Stick a row tagged 4K doesn't play.
            graph.database.movies().upsertAll(
                listOf(film(1, "4K-TOP - Superbad (2007)"), film(2, "EN - Superbad (2007)")),
            )
            graph.viewerLibrary.recordProgress("movie:2", positionMillis = 600_000, durationMillis = EPISODE_MILLIS)
            val vm = VodPlayerViewModel(app, graph)

            vm.attach(MediaKey.Movie(1), playback)

            assertThat(playback.played).containsExactly(MediaKey.Movie(2) to 600_000L)
            assertThat(vm.current.value).isEqualTo(MediaKey.Movie(2))
        }

    @Test
    fun `progress after stepping down belongs to the Quality now playing`() = runTest {
        graph.database.movies().upsertAll(listOf(film(1, "HD - Superbad (2007)"), film(2, "SD - Superbad (2007)")))
        val vm = VodPlayerViewModel(app, graph)
        vm.attach(MediaKey.Movie(1), playback)

        playback.steppedDownTo = MediaKey.Movie(2)
        vm.poll()
        playback.positionMillis = 300_000
        vm.saveProgress()

        assertThat(vm.current.value).isEqualTo(MediaKey.Movie(2))
        assertThat(graph.viewerLibrary.resumeFrom("movie:2")).isEqualTo(300_000L)
        assertThat(graph.viewerLibrary.resumeFrom("movie:1")).isEqualTo(0L)
    }

    @Test
    fun `switching Version in the player carries on from the same position`() = runTest {
        graph.database.sources().insert(
            Source(id = 1, name = "Provider", kind = SourceKind.XTREAM, url = "http://example.com"),
        )
        graph.database.movies().upsertAll(listOf(film(1, "EN - Superbad (2007)"), film(2, "DE - Superbad HD (2007)")))
        val vm = VodPlayerViewModel(app, graph)
        vm.attach(MediaKey.Movie(1), playback)
        assertThat(
            vm.qualityChoices.value.map {
                it.key
            },
        ).containsExactly(MediaKey.Movie(2), MediaKey.Movie(1)).inOrder()

        playback.positionMillis = 1_200_000
        vm.chooseVersion(MediaKey.Movie(2))

        assertThat(playback.played.last()).isEqualTo(MediaKey.Movie(2) to 1_200_000L)
        assertThat(vm.current.value).isEqualTo(MediaKey.Movie(2))
    }

    @Test
    fun `a film that reaches its end is finished`() = runTest {
        graph.database.movies().upsertAll(listOf(film(1, "EN - Superbad (2007)")))
        val vm = VodPlayerViewModel(app, graph)
        vm.attach(MediaKey.Movie(1), playback)

        vm.poll()
        assertThat(vm.finished.value).isFalse()

        playback.ended = true
        vm.poll()
        assertThat(vm.finished.value).isTrue()
    }

    @Test
    fun `an episode that reaches its end moves on rather than finishing`() = runTest {
        graph.database.episodes().upsertAll((1..2).map { testEpisode(1, it) })
        val vm = VodPlayerViewModel(app, graph)
        vm.attach(testEpisode(1, 1).key, playback)

        playback.positionMillis = playback.durationMillis
        playback.ended = true
        vm.poll()

        assertThat(vm.finished.value).isFalse()
        assertThat(vm.current.value).isEqualTo(testEpisode(1, 2).key)
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

    @Test
    fun `Mark watched on a movie leaves the player, and it's still Watched after the player saves as it closes`() =
        runTest {
            val vm = VodPlayerViewModel(app, graph)
            vm.attach(MediaKey.Movie(7), playback)
            playback.positionMillis = 900_000

            val leaves = vm.markWatched()
            vm.saveProgress()

            assertThat(leaves).isTrue()
            assertThat(graph.viewerLibrary.marks.statusOf("movie:7")).isEqualTo(WatchStatus.Watched)
        }

    @Test
    fun `Mark watched on an episode starts the Next Episode, and the one marked stays Watched`() = runTest {
        graph.database.episodes().upsertAll((1..3).map { testEpisode(1, it) })
        val vm = VodPlayerViewModel(app, graph)
        vm.attach(testEpisode(1, 1).key, playback)
        playback.positionMillis = 600_000

        val leaves = vm.markWatched()
        vm.saveProgress()

        assertThat(leaves).isFalse()
        assertThat(vm.current.value).isEqualTo(testEpisode(1, 2).key)
        assertThat(graph.viewerLibrary.episodeStatuses().first()[11]).isEqualTo(WatchStatus.Watched)
    }

    @Test
    fun `Mark watched on an episode whose length isn't known yet still stays Watched`() = runTest {
        graph.database.episodes().upsertAll((1..3).map { testEpisode(1, it) })
        val vm = VodPlayerViewModel(app, graph)
        vm.attach(testEpisode(1, 1).key, playback)
        playback.durationMillis = 0

        vm.markWatched()

        assertThat(vm.current.value).isEqualTo(testEpisode(1, 2).key)
        assertThat(graph.viewerLibrary.episodeStatuses().first()[11]).isEqualTo(WatchStatus.Watched)
    }

    @Test
    fun `Mark watched on a series' last episode leaves the player`() = runTest {
        graph.database.episodes().upsertAll((1..2).map { testEpisode(1, it) })
        val vm = VodPlayerViewModel(app, graph)
        vm.attach(testEpisode(1, 2).key, playback)
        playback.positionMillis = 600_000

        val leaves = vm.markWatched()
        vm.saveProgress()

        assertThat(leaves).isTrue()
        assertThat(playback.played).containsExactly(testEpisode(1, 2).key to 0L)
        assertThat(graph.viewerLibrary.episodeStatuses().first()[12]).isEqualTo(WatchStatus.Watched)
    }

    @Test
    fun `the Next Episode's progress still saves after moving on from a mark`() = runTest {
        graph.database.episodes().upsertAll((1..3).map { testEpisode(1, it) })
        val vm = VodPlayerViewModel(app, graph)
        vm.attach(testEpisode(1, 1).key, playback)

        vm.markWatched()
        playback.positionMillis = 300_000
        vm.saveProgress()

        assertThat(graph.viewerLibrary.resumeFrom("ep:12")).isEqualTo(300_000L)
    }

    @Test
    fun `movies, add-on streams and episodes can be marked until they're Watched`() = runTest {
        graph.database.episodes().upsertAll(listOf(testEpisode(1, 1)))
        val vm = VodPlayerViewModel(app, graph)

        vm.attach(MediaKey.Movie(7), playback)
        assertThat(vm.canMarkWatched.value).isTrue()

        vm.attach(MediaKey.AddonStream(8, "https://example.com/8.mkv"), playback)
        assertThat(vm.canMarkWatched.value).isTrue()

        vm.attach(testEpisode(1, 1).key, playback)
        assertThat(vm.canMarkWatched.value).isTrue()

        graph.viewerLibrary.marks.markWatched("movie:9")
        vm.attach(MediaKey.Movie(9), playback)
        assertThat(vm.canMarkWatched.value).isFalse()
    }

    @Test
    fun `a title played past 93 percent stops offering Mark watched once that's saved`() = runTest {
        val vm = VodPlayerViewModel(app, graph)
        vm.attach(MediaKey.Movie(7), playback)
        playback.positionMillis = EPISODE_MILLIS - 60_000

        vm.saveProgress()

        assertThat(vm.canMarkWatched.value).isFalse()
    }

    @Test
    fun `recordings and catch-up have no watch state to mark`() = runTest {
        val vm = VodPlayerViewModel(app, graph)

        vm.attach(MediaKey.Recording(3), playback)
        assertThat(vm.canMarkWatched.value).isFalse()

        vm.attach(MediaKey.Catchup(1, 1_000), playback)
        assertThat(vm.canMarkWatched.value).isFalse()
    }

    @Test
    fun `leaving just as Mark watched is chosen doesn't lose the mark`() = runTest {
        val vm = VodPlayerViewModel(app, graph)
        vm.attach(MediaKey.Movie(7), playback)

        val marking = launch(UnconfinedTestDispatcher(testScheduler)) { vm.markWatched() }
        marking.cancel()
        marking.join()

        assertThat(graph.viewerLibrary.marks.statusOf("movie:7")).isEqualTo(WatchStatus.Watched)
    }

    @Test
    fun `a title marked earlier saves progress again when it's opened anew`() = runTest {
        val vm = VodPlayerViewModel(app, graph)
        vm.attach(MediaKey.Movie(7), playback)
        vm.markWatched()
        vm.attach(MediaKey.Movie(8), playback)
        vm.attach(MediaKey.Movie(7), playback)

        playback.positionMillis = 600_000
        vm.saveProgress()

        assertThat(graph.viewerLibrary.resumeFrom("movie:7")).isEqualTo(600_000L)
    }

    /** Episode 1 of 3 with a resume point, opened from the start. */
    private suspend fun playFromStart(vm: VodPlayerViewModel = VodPlayerViewModel(app, graph)): VodPlayerViewModel {
        graph.database.episodes().upsertAll((1..3).map { testEpisode(1, it) })
        graph.viewerLibrary.recordProgress("ep:11", positionMillis = OLD_RESUME_POINT, durationMillis = EPISODE_MILLIS)
        vm.attach(testEpisode(1, 1).key, playback, fromStart = true)
        return vm
    }

    @Test
    fun `Play from start starts at 0 rather than the resume point`() = runTest {
        playFromStart()

        assertThat(playback.played).containsExactly(testEpisode(1, 1).key to 0L)
    }

    @Test
    fun `stopping after the first 5 seconds of Play from start records the new position`() = runTest {
        val vm = playFromStart()
        playback.positionMillis = 60_000

        vm.saveProgress()

        assertThat(graph.viewerLibrary.resumeFrom("ep:11")).isEqualTo(60_000L)
    }

    @Test
    fun `stopping within 5 seconds of Play from start leaves the old resume point`() = runTest {
        val vm = playFromStart()
        playback.positionMillis = 4_000

        vm.saveProgress()

        assertThat(graph.viewerLibrary.resumeFrom("ep:11")).isEqualTo(OLD_RESUME_POINT)
    }

    @Test
    fun `a player rebuilt before Play from start knows its position stays at the start, however many times`() =
        runTest {
            val vm = playFromStart()
            val route = testEpisode(1, 1).key

            val rebuilt = FakePlayback()
            vm.attach(route, rebuilt, fromStart = true)
            val rebuiltAgain = FakePlayback()
            vm.attach(route, rebuiltAgain, fromStart = true)

            assertThat(rebuilt.played).containsExactly(route to 0L)
            assertThat(rebuiltAgain.played).containsExactly(route to 0L)
        }

    @Test
    fun `a second rebuild in the first seconds of Play from start still doesn't jump to the old resume point`() =
        runTest {
            val vm = playFromStart()
            val route = testEpisode(1, 1).key
            playback.positionMillis = 3_000
            vm.poll()
            vm.attach(route, FakePlayback(), fromStart = true)

            // This player hasn't reported a position either, and 3 seconds was too early to save.
            val rebuiltAgain = FakePlayback()
            vm.attach(route, rebuiltAgain, fromStart = true)

            assertThat(rebuiltAgain.played).containsExactly(route to 0L)
        }

    @Test
    fun `once Play from start has saved a position, a rebuild that knows no position resumes from it`() = runTest {
        val vm = playFromStart()
        val route = testEpisode(1, 1).key
        playback.positionMillis = 60_000
        vm.saveProgress()
        vm.attach(route, FakePlayback(), fromStart = true)

        val rebuiltAgain = FakePlayback()
        vm.attach(route, rebuiltAgain, fromStart = true)

        assertThat(rebuiltAgain.played).containsExactly(route to 60_000L)
    }

    @Test
    fun `the view model Android builds keeps Play from start spent across a restore`() = runTest {
        val shared = ServiceLocator.get(app)
        val movie = MediaKey.Movie(77)
        shared.viewerLibrary.recordProgress(movie.stored, OLD_RESUME_POINT, EPISODE_MILLIS)
        val first = Robolectric.buildActivity(ComponentActivity::class.java).setup()
        val opened = FakePlayback()
        ViewModelProvider(first.get())[VodPlayerViewModel::class.java].attach(movie, opened, fromStart = true)
        val saved = Bundle().also(first::saveInstanceState)

        val restored = FakePlayback()
        val second = Robolectric.buildActivity(ComponentActivity::class.java).setup(saved)
        ViewModelProvider(second.get())[VodPlayerViewModel::class.java].attach(movie, restored, fromStart = true)

        assertThat(opened.played).containsExactly(movie to 0L)
        assertThat(restored.played).containsExactly(movie to OLD_RESUME_POINT)
    }

    @Test
    fun `Android restoring the app after Play from start resumes the progress made since`() = runTest {
        val saved = SavedStateHandle()
        val vm = playFromStart(VodPlayerViewModel(app, graph, savedState = saved))
        playback.positionMillis = 60_000
        vm.saveProgress()

        // The route still says from the start, and only the saved state outlives the process.
        val restored = FakePlayback()
        VodPlayerViewModel(app, graph, savedState = saved).attach(testEpisode(1, 1).key, restored, fromStart = true)

        assertThat(restored.played).containsExactly(testEpisode(1, 1).key to 60_000L)
    }

    @Test
    fun `a Next Episode following Play from start resumes as normal, even if the player is then rebuilt`() = runTest {
        val vm = playFromStart()
        graph.viewerLibrary.recordProgress("ep:12", positionMillis = 600_000, durationMillis = EPISODE_MILLIS)

        vm.playToEnd()
        val rebuilt = FakePlayback()
        vm.attach(testEpisode(1, 1).key, rebuilt, fromStart = true)

        assertThat(playback.played.last()).isEqualTo(testEpisode(1, 2).key to 600_000L)
        assertThat(rebuilt.played).containsExactly(testEpisode(1, 2).key to 600_000L)
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

    private companion object {
        const val OLD_RESUME_POINT = 900_000L
    }
}
