/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv

import android.os.Looper
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.pressKey
import androidx.media3.common.util.UnstableApi
import androidx.test.core.app.ApplicationProvider
import androidx.test.espresso.Espresso
import app.opentv.core.ServiceLocator
import app.opentv.core.SleepTimer
import app.opentv.data.repo.MediaKey
import app.opentv.player.NextEpisodeCountdown
import app.opentv.player.PlayerController
import app.opentv.ui.player.SubtitlePicks
import app.opentv.ui.vod.VodPlayerContent
import app.opentv.ui.vod.VodPlayerViewModel
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The on-demand player's control row, as the remote moves along it: the seek bar picked up before it
 * moves, put down with OK or Back or by waiting, and an episode's Previous and Next episode buttons.
 */
// Plain Application: OpenTvApp schedules WorkManager on start, which lone composables don't need.
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = TV_1080P, application = android.app.Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@OptIn(ExperimentalTestApi::class, UnstableApi::class)
class VodPlayerRowTest {

    @get:Rule
    val compose = createComposeRule()

    private val app: android.app.Application = ApplicationProvider.getApplicationContext()
    private val players = TestPlayers(app)
    private val controller = players.controller()

    private val graph = ServiceLocator.Graph(app)
    private val viewModel = VodPlayerViewModel(app, graph)
    private val noPicks = SubtitlePicks(onOff = {}, onPicked = {})

    @After
    fun release() {
        // A process-wide singleton: a timer left armed would close the next test's player.
        SleepTimer.clear()
        controller.release()
        players.close()
        graph.database.close()
    }

    private fun showFilm(growingRec: Boolean = false) {
        compose.setTvContent {
            VodPlayerContent(controller, viewModel, growingRec, noPicks, onBack = {})
        }
        players.play(controller)
        compose.waitForIdle()
    }

    private fun hideControls() {
        compose.mainClock.advanceTimeBy(CONTROLS_TIMEOUT_MILLIS)
        compose.onNodeWithContentDescription("Pause").assertDoesNotExist()
    }

    private fun press(key: Key) {
        compose.onRoot().performKeyInput { pressKey(key) }
        compose.waitForIdle()
    }

    /** A film [TEN_MINUTES] in, with the controls up and focus on play/pause. */
    private fun showFilmPartWay() {
        showFilm()
        controller.player.seekTo(TEN_MINUTES)
        players.untilReady(controller.player)
        settle()
    }

    /** Lets the controls take focus and the bar read the player again. */
    private fun settle() {
        compose.mainClock.advanceTimeBy(SETTLE_MILLIS)
        compose.waitForIdle()
    }

    private fun barPosition() = compose
        .onNode(seekBar)
        .fetchSemanticsNode()
        .config[SemanticsProperties.ProgressBarRangeInfo]
        .current
        .toLong()

    // What the screen hands the view model, so moving on to something else reaches the same player.
    private val playback = playbackOn(controller)

    private fun playbackOn(player: PlayerController) = object : VodPlayerViewModel.Playback {
        override fun progress() = NextEpisodeCountdown.Playback(
            player.player.currentPosition,
            player.player.duration.coerceAtLeast(0),
        )

        override fun position() = player.player.currentPosition

        override fun play(request: PlayerController.Request, skipped: String?) = player.play(request, debounce = false)

        override fun pause() = Unit

        override fun stop() = Unit
    }

    /** The player on [key], with a film the view model only knows as far as [fake] tells it. */
    private fun showWatching(key: MediaKey, fake: VodPlayerViewModel.Playback = playback, onBack: () -> Unit = {}) {
        compose.setTvContent { VodPlayerContent(controller, viewModel, false, noPicks, onBack) }
        players.play(controller)
        runBlocking { viewModel.attach(key, fake) }
        compose.waitForIdle()
    }

    /** On the bar, picked up and moved one [STEP] on from where playback is. */
    private fun startSeekingOnTheBar() {
        press(Key.DirectionCenter)
        press(Key.DirectionRight)
        settle()
    }

    private fun dragTheBar() {
        compose.onNode(seekBar).performTouchInput {
            down(center)
            moveBy(Offset(100f, 0f))
        }
        compose.waitForIdle()
    }

    /** Waits for [done], running the main thread's queue meanwhile. */
    private fun untilOnMain(done: () -> Boolean) {
        val until = System.currentTimeMillis() + WAIT_MILLIS
        while (!done()) {
            check(System.currentTimeMillis() < until) { "Still waiting after $WAIT_MILLIS ms" }
            Thread.sleep(POLL_MILLIS)
            Shadows.shadowOf(Looper.getMainLooper()).idle()
        }
        compose.waitForIdle()
    }

    /** Episode 2 of three, or [number], with the controls up and the Highlight on the bar. */
    private fun showEpisode(number: Int = 2, fake: FakePlayback = FakePlayback()) {
        runBlocking { graph.database.episodes().upsertAll((1..3).map { testEpisode(1, it) }) }
        showWatching(testEpisode(1, number).key, fake)
        settle()
    }

    @Test
    fun `Left and Right from the bar move along a film's row, play, the bar, Subtitles, Audio, without seeking`() {
        showFilmPartWay()

        press(Key.DirectionLeft)
        compose.onNodeWithContentDescription("Pause").assertIsFocused()
        press(Key.DirectionLeft)
        compose.onNodeWithContentDescription("Pause").assertIsFocused()

        press(Key.DirectionRight)
        assertThat(compose.seekBarIsFocused()).isTrue()
        press(Key.DirectionRight)
        compose.onNodeWithContentDescription("Subtitles").assertIsFocused()
        press(Key.DirectionRight)
        compose.onNodeWithContentDescription("Audio").assertIsFocused()

        assertThat(controller.player.currentPosition).isEqualTo(TEN_MINUTES)
        assertThat(barPosition()).isEqualTo(TEN_MINUTES)
    }

    @Test
    fun `OK picks the bar up, and Left and Right then move it while the film stays where it was`() {
        showFilmPartWay()

        press(Key.DirectionCenter)
        press(Key.DirectionRight)
        press(Key.DirectionRight)
        settle()

        assertThat(barPosition()).isEqualTo(TEN_MINUTES + 2 * STEP)
        assertThat(controller.player.currentPosition).isEqualTo(TEN_MINUTES)
        assertThat(controller.player.playWhenReady).isTrue()
        assertThat(compose.seekBarIsFocused()).isTrue()
        captureScreen("vodplayer_seek_picked_up")
    }

    @Test
    fun `OK puts the bar down and playback jumps there, with the Highlight still on the bar`() {
        showFilmPartWay()
        startSeekingOnTheBar()

        press(Key.DirectionCenter)
        players.untilReady(controller.player)
        settle()

        assertThat(controller.player.currentPosition).isEqualTo(TEN_MINUTES + STEP)
        assertThat(barPosition()).isEqualTo(TEN_MINUTES + STEP)
        assertThat(compose.seekBarIsFocused()).isTrue()

        // Put down, Right moves along the row again, and the bar goes on following playback.
        press(Key.DirectionRight)
        compose.onNodeWithContentDescription("Subtitles").assertIsFocused()
        controller.player.seekTo(TEN_MINUTES * 2)
        players.untilReady(controller.player)
        settle()
        assertThat(barPosition()).isEqualTo(TEN_MINUTES * 2)
    }

    @Test
    fun `Back puts the bar down where it started, without hiding the controls or leaving`() {
        var left = false
        compose.setTvContent { VodPlayerContent(controller, viewModel, false, noPicks, onBack = { left = true }) }
        players.play(controller)
        controller.player.seekTo(TEN_MINUTES)
        players.untilReady(controller.player)
        settle()
        startSeekingOnTheBar()

        Espresso.pressBack()
        settle()

        assertThat(barPosition()).isEqualTo(TEN_MINUTES)
        assertThat(controller.player.currentPosition).isEqualTo(TEN_MINUTES)
        compose.onNodeWithContentDescription("Pause").assertExists()
        assertThat(compose.seekBarIsFocused()).isTrue()
        assertThat(left).isFalse()
    }

    @Test
    fun `a bar picked up and left alone goes back to where it started, keeping the Highlight`() {
        showFilmPartWay()
        // Settling after the last press has already used SETTLE_MILLIS of the wait.
        startSeekingOnTheBar()
        compose.mainClock.advanceTimeBy(WITHIN_GRACE_MILLIS - SETTLE_MILLIS)
        assertThat(barPosition()).isEqualTo(TEN_MINUTES + STEP)

        compose.mainClock.advanceTimeBy(PAST_GRACE_MILLIS - WITHIN_GRACE_MILLIS)
        compose.waitForIdle()

        assertThat(barPosition()).isEqualTo(TEN_MINUTES)
        assertThat(controller.player.currentPosition).isEqualTo(TEN_MINUTES)
        assertThat(compose.seekBarIsFocused()).isTrue()

        compose.mainClock.advanceTimeBy(CONTROLS_TIMEOUT_MILLIS)
        compose.onNodeWithContentDescription("Pause").assertDoesNotExist()
    }

    @Test
    fun `OK on the bar doesn't pause, and OK on play does`() {
        showFilmPartWay()

        press(Key.DirectionCenter)
        assertThat(controller.player.playWhenReady).isTrue()
        press(Key.DirectionCenter)

        press(Key.DirectionLeft)
        press(Key.DirectionCenter)
        assertThat(controller.player.playWhenReady).isFalse()
        compose.onNodeWithContentDescription("Play").assertIsFocused()
    }

    @Test
    fun `a finger resting on the bar keeps the controls up, and lifting it finishes the seek`() {
        showFilmPartWay()
        dragTheBar()
        val target = barPosition()
        assertThat(target).isNotEqualTo(TEN_MINUTES)

        compose.mainClock.advanceTimeBy(CONTROLS_TIMEOUT_MILLIS + PAST_GRACE_MILLIS)
        compose.onNodeWithContentDescription("Pause").assertExists()
        assertThat(barPosition()).isEqualTo(target)

        compose.onNode(seekBar).performTouchInput { up() }
        compose.waitForIdle()

        assertThat(controller.player.currentPosition).isEqualTo(target)
    }

    @Test
    fun `a bar left picked up on a paused film doesn't stay there`() {
        showFilmPartWay()
        press(Key.MediaPause)
        startSeekingOnTheBar()

        compose.mainClock.advanceTimeBy(PAST_GRACE_MILLIS)
        compose.waitForIdle()

        compose.onNodeWithContentDescription("Play").assertExists()
        assertThat(barPosition()).isEqualTo(TEN_MINUTES)
        assertThat(controller.player.currentPosition).isEqualTo(TEN_MINUTES)
    }

    @Test
    fun `rewind and fast-forward on a picked-up bar seek at once and put it down`() {
        showFilmPartWay()
        startSeekingOnTheBar()

        press(Key.MediaFastForward)
        players.untilReady(controller.player)
        settle()

        assertThat(controller.player.currentPosition).isEqualTo(TEN_MINUTES + 15_000)
        assertThat(barPosition()).isEqualTo(TEN_MINUTES + 15_000)

        startSeekingOnTheBar()
        press(Key.MediaRewind)
        players.untilReady(controller.player)
        settle()

        assertThat(controller.player.currentPosition).isEqualTo(TEN_MINUTES)
        press(Key.DirectionRight)
        compose.onNodeWithContentDescription("Subtitles").assertIsFocused()
    }

    @Test
    fun `the Next Episode card coming up counts as leaving the bar`() {
        // 24 s before the end of the hour: the card's up, with 9 s of its countdown left (no timings,
        // so it runs from 30 to 15 s before the end).
        val nearTheEnd = 3_576_000L
        runBlocking { graph.database.episodes().upsertAll((1..2).map { testEpisode(1, it) }) }
        compose.setTvContent { VodPlayerContent(controller, viewModel, false, noPicks, onBack = {}) }
        runBlocking { viewModel.attach(testEpisode(1, 1).key, playback) }
        players.untilReady(controller.player)
        controller.player.seekTo(nearTheEnd)
        players.untilReady(controller.player)
        settle()
        startSeekingOnTheBar()

        runBlocking { viewModel.poll() }
        compose.waitForIdle()
        compose.onNodeWithContentDescription("Pause").assertDoesNotExist()
        viewModel.cancel()
        compose.mainClock.advanceTimeBy(PAST_GRACE_MILLIS)
        press(Key.DirectionCenter)
        settle()

        assertThat(barPosition()).isEqualTo(nearTheEnd)
        assertThat(controller.player.currentPosition).isEqualTo(nearTheEnd)
    }

    @Test
    fun `a finger on the bar when the Next Episode card comes up doesn't leave the bar stuck`() {
        // 24 s before the end of the hour: the card's up, with 9 s of its countdown left (no timings,
        // so it runs from 30 to 15 s before the end).
        val nearTheEnd = 3_576_000L
        runBlocking { graph.database.episodes().upsertAll((1..2).map { testEpisode(1, it) }) }
        compose.setTvContent { VodPlayerContent(controller, viewModel, false, noPicks, onBack = {}) }
        runBlocking { viewModel.attach(testEpisode(1, 1).key, playback) }
        players.untilReady(controller.player)
        controller.player.seekTo(nearTheEnd)
        players.untilReady(controller.player)
        settle()
        dragTheBar()

        runBlocking { viewModel.poll() }
        compose.waitForIdle()
        viewModel.cancel()
        compose.mainClock.advanceTimeBy(PAST_GRACE_MILLIS)
        press(Key.DirectionCenter)
        settle()

        assertThat(barPosition()).isEqualTo(nearTheEnd)
        assertThat(controller.player.currentPosition).isEqualTo(nearTheEnd)
    }

    @Test
    fun `a new episode drops the seek at once, with no grace`() {
        compose.setTvContent { VodPlayerContent(controller, viewModel, false, noPicks, onBack = {}) }
        runBlocking { viewModel.attach(MediaKey.Movie(1), playback) }
        players.untilReady(controller.player)
        controller.player.seekTo(TEN_MINUTES)
        players.untilReady(controller.player)
        settle()
        startSeekingOnTheBar()
        assertThat(barPosition()).isEqualTo(TEN_MINUTES + STEP)

        runBlocking { viewModel.attach(MediaKey.Movie(2), playback) }
        players.untilReady(controller.player)
        settle()

        assertThat(barPosition()).isEqualTo(controller.player.currentPosition)
        assertThat(barPosition()).isLessThan(TEN_MINUTES)
    }

    @Test
    fun `an episode's row has Previous and Next episode either side of play, Next one Left from the bar`() {
        showEpisode()

        press(Key.DirectionLeft)
        compose.onNodeWithContentDescription("Next episode").assertIsFocused()
        captureScreen("vodplayer_controls_episode")
        press(Key.DirectionLeft)
        compose.onNodeWithContentDescription("Pause").assertIsFocused()
        press(Key.DirectionLeft)
        compose.onNodeWithContentDescription("Previous episode").assertIsFocused()
        press(Key.DirectionLeft)
        compose.onNodeWithContentDescription("Previous episode").assertIsFocused()
    }

    @Test
    fun `a show's first episode has no Previous episode, and its last no Next`() {
        showEpisode(number = 1)

        compose.onNodeWithContentDescription("Previous episode").assertDoesNotExist()
        press(Key.DirectionLeft)
        compose.onNodeWithContentDescription("Next episode").assertIsFocused()
        captureScreen("vodplayer_controls_first_episode")

        runBlocking { viewModel.attach(testEpisode(1, 3).key, FakePlayback()) }
        compose.waitForIdle()
        compose.onNodeWithContentDescription("Next episode").assertDoesNotExist()
        compose.onNodeWithContentDescription("Previous episode").assertExists()
    }

    @Test
    fun `Next episode plays the following episode, and Previous episode the one before`() {
        val fake = FakePlayback()
        showEpisode(fake = fake)
        press(Key.DirectionLeft)

        press(Key.DirectionCenter)
        // The buttons follow once the move has worked out the new episode's neighbours, after it starts.
        compose.waitUntil(WAIT_MILLIS) { viewModel.episodeSteps.value.of == testEpisode(1, 3).key }

        compose.onNodeWithContentDescription("Next episode").assertDoesNotExist()
        compose.onNodeWithContentDescription("Pause").assertIsFocused()
        press(Key.DirectionLeft)
        press(Key.DirectionCenter)
        // The move finishes on whichever thread the database answered on, so what's playing can
        // change a moment before the player is asked: wait for the player itself.
        compose.waitUntil(WAIT_MILLIS) { fake.played.size == 3 }
        assertThat(fake.played.map { it.first }).containsExactly(
            testEpisode(1, 2).key,
            testEpisode(1, 3).key,
            testEpisode(1, 2).key,
        ).inOrder()
    }

    @Test
    fun `the remote's next and previous keys move between episodes with the controls hidden`() {
        showEpisode()
        hideControls()

        press(Key.MediaNext)
        compose.waitUntil(WAIT_MILLIS) { viewModel.current.value == testEpisode(1, 3).key }
        press(Key.MediaPrevious)
        compose.waitUntil(WAIT_MILLIS) { viewModel.current.value == testEpisode(1, 2).key }
        press(Key.MediaPrevious)
        compose.waitUntil(WAIT_MILLIS) { viewModel.current.value == testEpisode(1, 1).key }
    }

    @Test
    fun `episode buttons found while the controls show wait until they next open`() {
        val panel = FakePanel()
        val fetching = ServiceLocator.Graph(app, http = panel.http)
        val loading = VodPlayerViewModel(app, fetching)
        try {
            runBlocking {
                fetching.database.sources().insert(PANEL_SOURCE)
                fetching.database.episodes().upsertAll(listOf(testEpisode(1, 2)))
            }
            panel.replies["get_series_info:s"] = SERIES_OF_THREE
            val hold = panel.hold("get_series_info:s")
            compose.setTvContent { VodPlayerContent(controller, loading, false, noPicks, onBack = {}) }
            players.play(controller)
            runBlocking { loading.attach(testEpisode(1, 2).key, FakePlayback()) }
            settle()

            hold.release()
            // The reply comes back on another thread and finishes on the main one, whose queue
            // waitUntil doesn't run, so this runs it while waiting.
            untilOnMain { loading.episodeSteps.value.next }
            compose.onNodeWithContentDescription("Next episode").assertDoesNotExist()
            compose.onNodeWithContentDescription("Previous episode").assertDoesNotExist()

            hideControls()
            press(Key.DirectionCenter)
            settle()
            compose.onNodeWithContentDescription("Next episode").assertExists()
            compose.onNodeWithContentDescription("Previous episode").assertExists()
        } finally {
            fetching.database.close()
        }
    }

    @Test
    fun `a film has no episode buttons, and its next key does nothing`() {
        showFilmPartWay()

        compose.onNodeWithContentDescription("Next episode").assertDoesNotExist()
        compose.onNodeWithContentDescription("Previous episode").assertDoesNotExist()
        press(Key.MediaNext)
        assertThat(controller.player.currentPosition).isEqualTo(TEN_MINUTES)
    }

    private companion object {
        const val WAIT_MILLIS = 5_000L
        const val CONTROLS_TIMEOUT_MILLIS = 6_000L
        const val TEN_MINUTES = 600_000L
        const val SETTLE_MILLIS = 600L
        const val STEP = 10_000L
        const val WITHIN_GRACE_MILLIS = 2_800L
        const val PAST_GRACE_MILLIS = 3_200L
        const val POLL_MILLIS = 20L
    }
}
