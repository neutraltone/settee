/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.isFocused
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.pressKey
import androidx.media3.common.util.UnstableApi
import androidx.test.core.app.ApplicationProvider
import app.opentv.core.ServiceLocator
import app.opentv.data.repo.MediaKey
import app.opentv.data.repo.Playable
import app.opentv.player.NextEpisodeCountdown
import app.opentv.player.PlayerController
import app.opentv.ui.player.SubtitlePicks
import app.opentv.ui.vod.VodPlayerContent
import app.opentv.ui.vod.VodPlayerViewModel
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

// Plain Application: OpenTvApp schedules WorkManager on start, which lone composables don't need.
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = TV_1080P, application = android.app.Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@OptIn(ExperimentalTestApi::class, UnstableApi::class)
class VodPlayerContentTest {

    @get:Rule
    val compose = createComposeRule()

    private val app: android.app.Application = ApplicationProvider.getApplicationContext()
    private val players = TestPlayers(app)
    private val controller = players.controller()

    // Never attached to an episode, so no Next Episode card: these tests are about the transport.
    private val graph = ServiceLocator.Graph(app)
    private val viewModel = VodPlayerViewModel(app, graph)
    private val noPicks = SubtitlePicks(onOff = {}, onPicked = {})

    @After
    fun release() {
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
        compose.onNodeWithText("Pause").assertDoesNotExist()
    }

    private fun press(key: Key) {
        compose.onRoot().performKeyInput { pressKey(key) }
        compose.waitForIdle()
    }

    @Test
    fun `play-pause pauses a playing film with the controls hidden, and shows them`() {
        showFilm()
        hideControls()

        press(Key.MediaPlayPause)

        assertThat(controller.player.playWhenReady).isFalse()
        compose.onNodeWithText("Play").assertExists()
        captureScreen("vod_player_paused")
    }

    @Test
    fun `pause only pauses and play only resumes, with the controls showing`() {
        showFilm()

        press(Key.MediaPause)
        press(Key.MediaPause)
        assertThat(controller.player.playWhenReady).isFalse()

        press(Key.MediaPlay)
        press(Key.MediaPlay)
        assertThat(controller.player.playWhenReady).isTrue()
        compose.onNodeWithText("Pause").assertExists()
    }

    @Test
    fun `rewind and fast-forward skip by 15 seconds a press, with the controls hidden`() {
        showFilm()
        controller.player.seekTo(60_000)
        players.untilReady(controller.player)
        compose.waitForIdle()
        hideControls()

        press(Key.MediaRewind)
        press(Key.MediaRewind)
        assertThat(controller.player.currentPosition).isEqualTo(30_000)

        press(Key.MediaFastForward)
        assertThat(controller.player.currentPosition).isEqualTo(45_000)
    }

    @Test
    fun `fast-forward on a recording still being written stops at what's recorded`() {
        val recording = players.controller(RECORDING)
        compose.setTvContent {
            VodPlayerContent(recording, viewModel, growingRec = true, noPicks, onBack = {})
        }
        players.play(recording)
        val recorded = recording.player.bufferedPosition
        recording.player.seekTo(recorded - 20_000)
        players.untilReady(recording.player)

        press(Key.MediaFastForward)
        assertThat(recording.player.currentPosition).isEqualTo(recorded - 5_000)
        // ExoPlayer parks a seek to the very end of a window a millisecond short of it.
        press(Key.MediaFastForward)
        assertThat(
            recording.player.currentPosition,
        ).isIn(com.google.common.collect.Range.closed(recorded - 1, recorded))

        recording.release()
    }

    @Test
    fun `the bar follows the player when something else resumes it`() {
        showFilm()
        press(Key.MediaPlayPause)

        controller.player.playWhenReady = true
        compose.waitForIdle()
        compose.onNodeWithText("Pause").assertExists()

        press(Key.MediaPlayPause)
        assertThat(controller.player.playWhenReady).isFalse()
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

    // The buffering spinner is a progress node too, but one with no range.
    private val seekBar = SemanticsMatcher("is the seek bar") { node ->
        node.config.getOrNull(SemanticsProperties.ProgressBarRangeInfo)?.range?.endInclusive?.let { it > 0 } == true
    }

    private fun barPosition() = compose
        .onNode(seekBar)
        .fetchSemanticsNode()
        .config[SemanticsProperties.ProgressBarRangeInfo]
        .current
        .toLong()

    private fun barIsFocused() = compose
        .onAllNodes(isFocused() and seekBar)
        .fetchSemanticsNodes()
        .isNotEmpty()

    @Test
    fun `reaching the bar shows where the film is and leaves it playing from there`() {
        showFilmPartWay()

        press(Key.DirectionUp)
        settle()

        assertThat(controller.player.currentPosition).isEqualTo(TEN_MINUTES)
        assertThat(barPosition()).isEqualTo(TEN_MINUTES)
        captureScreen("vod_player_seek_bar")
    }

    @Test
    fun `down from the bar goes to the buttons without seeking, and up comes back`() {
        showFilmPartWay()
        press(Key.DirectionUp)
        settle()

        press(Key.DirectionDown)
        settle()

        assertThat(controller.player.currentPosition).isEqualTo(TEN_MINUTES)
        assertThat(barIsFocused()).isFalse()
        compose.onNodeWithText("Pause").assertIsFocused()

        press(Key.DirectionUp)
        settle()

        assertThat(controller.player.currentPosition).isEqualTo(TEN_MINUTES)
        assertThat(barIsFocused()).isTrue()
    }

    /** On the bar, with right held down and not yet let go: a seek 1% of the film on from [TEN_MINUTES]. */
    private fun startSeekingOnTheBar() {
        press(Key.DirectionUp)
        settle()
        compose.onRoot().performKeyInput { keyDown(Key.DirectionRight) }
        settle()
    }

    private fun letGoOfRight() {
        compose.onRoot().performKeyInput { keyUp(Key.DirectionRight) }
        compose.waitForIdle()
    }

    @Test
    fun `leaving the bar mid-seek and returning in time carries on from where the seek had got to`() {
        showFilmPartWay()
        startSeekingOnTheBar()
        assertThat(barPosition()).isEqualTo(TEN_MINUTES + ONE_PERCENT)
        captureScreen("vod_player_seek_bar_seeking")

        press(Key.DirectionDown)
        letGoOfRight()
        compose.mainClock.advanceTimeBy(WITHIN_GRACE_MILLIS)
        press(Key.DirectionUp)
        compose.waitForIdle()

        assertThat(barPosition()).isEqualTo(TEN_MINUTES + ONE_PERCENT)
        assertThat(controller.player.currentPosition).isEqualTo(TEN_MINUTES)

        press(Key.DirectionRight)

        assertThat(controller.player.currentPosition).isEqualTo(TEN_MINUTES + 2 * ONE_PERCENT)
    }

    @Test
    fun `not returning in time drops the seek, and playback stays where it was`() {
        showFilmPartWay()
        startSeekingOnTheBar()

        press(Key.DirectionDown)
        letGoOfRight()
        compose.mainClock.advanceTimeBy(PAST_GRACE_MILLIS)
        compose.waitForIdle()

        assertThat(barPosition()).isEqualTo(TEN_MINUTES)
        assertThat(controller.player.currentPosition).isEqualTo(TEN_MINUTES)
    }

    @Test
    fun `a finished seek moves playback, and the bar goes on following it`() {
        showFilmPartWay()
        press(Key.DirectionUp)
        settle()

        press(Key.DirectionRight)
        players.untilReady(controller.player)
        settle()
        assertThat(controller.player.currentPosition).isEqualTo(TEN_MINUTES + ONE_PERCENT)
        assertThat(barPosition()).isEqualTo(TEN_MINUTES + ONE_PERCENT)

        controller.player.seekTo(TEN_MINUTES * 2)
        players.untilReady(controller.player)
        settle()
        assertThat(barPosition()).isEqualTo(TEN_MINUTES * 2)
    }

    @Test
    fun `a seek whose key-up never comes is dropped, on the bar, and the controls still hide`() {
        showFilmPartWay()
        startSeekingOnTheBar()

        compose.mainClock.advanceTimeBy(PAST_GRACE_MILLIS)
        compose.waitForIdle()
        assertThat(barPosition()).isEqualTo(TEN_MINUTES)

        compose.mainClock.advanceTimeBy(CONTROLS_TIMEOUT_MILLIS)
        compose.onNodeWithText("Pause").assertDoesNotExist()
        press(Key.DirectionCenter)
        settle()

        assertThat(barPosition()).isEqualTo(TEN_MINUTES)
        assertThat(controller.player.currentPosition).isEqualTo(TEN_MINUTES)
    }

    @Test
    fun `a finger resting on the bar keeps the controls up, and lifting it finishes the seek`() {
        showFilmPartWay()
        dragTheBar()
        val target = barPosition()
        assertThat(target).isNotEqualTo(TEN_MINUTES)

        compose.mainClock.advanceTimeBy(CONTROLS_TIMEOUT_MILLIS + PAST_GRACE_MILLIS)
        compose.onNodeWithText("Pause").assertExists()
        assertThat(barPosition()).isEqualTo(target)

        compose.onNode(seekBar).performTouchInput { up() }
        compose.waitForIdle()

        assertThat(controller.player.currentPosition).isEqualTo(target)
    }

    private fun dragTheBar() {
        compose.onNode(seekBar).performTouchInput {
            down(center)
            moveBy(Offset(100f, 0f))
        }
        compose.waitForIdle()
    }

    @Test
    fun `a seek left unfinished on the bar of a paused film doesn't stay there`() {
        showFilmPartWay()
        press(Key.MediaPause)
        startSeekingOnTheBar()

        compose.mainClock.advanceTimeBy(PAST_GRACE_MILLIS)
        compose.waitForIdle()

        compose.onNodeWithText("Play").assertExists()
        assertThat(barPosition()).isEqualTo(TEN_MINUTES)
        assertThat(controller.player.currentPosition).isEqualTo(TEN_MINUTES)
    }

    @Test
    fun `fast-forward during a seek wins, and letting go of the key doesn't undo it`() {
        showFilmPartWay()
        startSeekingOnTheBar()

        press(Key.MediaFastForward)
        letGoOfRight()
        players.untilReady(controller.player)
        settle()

        assertThat(controller.player.currentPosition).isEqualTo(TEN_MINUTES + 15_000)
        assertThat(barPosition()).isEqualTo(TEN_MINUTES + 15_000)
    }

    // What the screen hands the view model, so moving on to something else reaches the same player.
    private val playback = playbackOn(controller)

    private fun playbackOn(player: PlayerController) = object : VodPlayerViewModel.Playback {
        override fun progress() = NextEpisodeCountdown.Playback(
            player.player.currentPosition,
            player.player.duration.coerceAtLeast(0),
        )

        override fun play(key: MediaKey, startPositionMillis: Long) =
            player.play(PlayerController.Request(key, startPositionMillis), debounce = false)

        override fun pause() = Unit

        override fun stop() = Unit
    }

    @Test
    fun `the Next Episode card coming up counts as leaving the bar`() {
        val nearTheEnd = 3_588_000L
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
        compose.onNodeWithText("Pause").assertDoesNotExist()
        viewModel.cancel()
        compose.mainClock.advanceTimeBy(PAST_GRACE_MILLIS)
        press(Key.DirectionCenter)
        settle()

        assertThat(barPosition()).isEqualTo(nearTheEnd)
        assertThat(controller.player.currentPosition).isEqualTo(nearTheEnd)
    }

    @Test
    fun `a finger on the bar when the Next Episode card comes up doesn't leave the bar stuck`() {
        val nearTheEnd = 3_588_000L
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
    fun `while the next one loads the bar shows where it will start, not the last one's position`() {
        val loaded = CompletableDeferred<Unit>()
        val slow = players.controller { key ->
            if (key == MediaKey.Movie(2)) loaded.await()
            Playable.Ready(key, "https://example.com/stream", userAgent = "", title = "Film", isLive = false)
        }
        val onSlow = playbackOn(slow)
        compose.setTvContent { VodPlayerContent(slow, viewModel, false, noPicks, onBack = {}) }
        runBlocking { viewModel.attach(MediaKey.Movie(1), onSlow) }
        players.untilReady(slow.player)
        slow.player.seekTo(TEN_MINUTES)
        players.untilReady(slow.player)
        settle()

        runBlocking { viewModel.attach(MediaKey.Movie(2), onSlow) }
        settle()

        assertThat(slow.player.currentPosition).isEqualTo(TEN_MINUTES)
        assertThat(barPosition()).isEqualTo(0)

        loaded.complete(Unit)
        players.until { slow.player.currentPosition < TEN_MINUTES }
        slow.release()
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
        assertThat(barPosition()).isEqualTo(TEN_MINUTES + ONE_PERCENT)

        runBlocking { viewModel.attach(MediaKey.Movie(2), playback) }
        players.untilReady(controller.player)
        settle()

        assertThat(barPosition()).isEqualTo(controller.player.currentPosition)
        assertThat(barPosition()).isLessThan(TEN_MINUTES)
    }

    private companion object {
        const val CONTROLS_TIMEOUT_MILLIS = 6_000L
        const val TEN_MINUTES = 600_000L
        const val SETTLE_MILLIS = 600L
        const val ONE_PERCENT = 36_000L
        const val WITHIN_GRACE_MILLIS = 2_800L
        const val PAST_GRACE_MILLIS = 3_200L
    }
}
