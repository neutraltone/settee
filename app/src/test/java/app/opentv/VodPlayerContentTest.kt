/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv

import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.media3.common.util.UnstableApi
import androidx.test.core.app.ApplicationProvider
import app.opentv.core.ServiceLocator
import app.opentv.ui.player.SubtitlePicks
import app.opentv.ui.vod.VodPlayerContent
import app.opentv.ui.vod.VodPlayerViewModel
import com.google.common.truth.Truth.assertThat
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

    private companion object {
        const val CONTROLS_TIMEOUT_MILLIS = 6_000L
    }
}
