/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.media3.common.util.UnstableApi
import androidx.test.core.app.ApplicationProvider
import androidx.test.espresso.Espresso
import app.opentv.core.AppSettings
import app.opentv.player.PlaybackQueue
import app.opentv.player.PlayerController
import app.opentv.ui.player.LivePlayerContent
import app.opentv.ui.player.SubtitlePicks
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
class LivePlayerContentTest {

    @get:Rule
    val compose = createComposeRule()

    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()
    private val players = TestPlayers(context)
    private val controller = players.controller(LIVE, dvr = true)

    private var turnedOn = 0
    private val played = mutableListOf<Long>()
    private var dontAskAgain = 0

    // What PlayerScreen reads from AppSettings; a test flips them the way the settings would change.
    private var livePause by mutableStateOf(true)
    private var promptDismissed by mutableStateOf(false)
    private var current by mutableStateOf(controller)

    @After
    fun release() {
        controller.release()
        players.close()
    }

    private fun showChannel(
        player: PlayerController = controller,
        livePause: Boolean = true,
        queue: List<PlaybackQueue.Item> = emptyList(),
        currentId: Long = 1L,
    ) {
        this.livePause = livePause
        current = player
        compose.setTvContent {
            LivePlayerContent(
                controller = current,
                settings = AppSettings.get(context),
                queue = queue,
                variants = emptyList(),
                currentId = currentId,
                previousId = null,
                recordingThis = false,
                livePause = this.livePause,
                pausePromptDismissed = promptDismissed,
                onPlayChannel = { played += it },
                onTune = {},
                onToggleRecord = {},
                onTurnOnLivePause = { turnedOn++ },
                onDontAskAgain = {
                    dontAskAgain++
                    promptDismissed = true
                },
                subtitlePicks = SubtitlePicks(onOff = {}, onPicked = {}),
                onBack = {},
            )
        }
        players.play(player, isLive = true)
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
    fun `with live pause on, play-pause pauses and resumes the channel`() {
        showChannel()
        hideControls()

        press(Key.MediaPlayPause)
        assertThat(controller.player.playWhenReady).isFalse()

        press(Key.MediaPlayPause)
        assertThat(controller.player.playWhenReady).isTrue()
    }

    @Test
    fun `with live pause on, rewind and fast-forward skip within the buffer`() {
        showChannel()
        controller.player.seekTo(60_000)
        players.untilReady(controller.player)
        hideControls()

        press(Key.MediaRewind)
        assertThat(controller.player.currentPosition).isEqualTo(45_000)

        press(Key.MediaFastForward)
        press(Key.MediaFastForward)
        assertThat(controller.player.currentPosition).isEqualTo(75_000)
    }

    @Test
    fun `with live pause on, pausing keeps the controls up counting down what's left of the buffer`() {
        showChannel()
        hideControls()

        press(Key.MediaPlayPause)
        compose.onNodeWithText("Paused · 2:00 left").assertExists()
        captureScreen("live_player_paused_countdown")

        compose.mainClock.advanceTimeBy(36_000)
        compose.onNodeWithText("Paused · 1:24 left").assertExists()
    }

    @Test
    fun `with live pause on, a pause that outlasts the buffer goes back to live`() {
        showChannel()
        controller.player.seekTo(30_000)
        players.untilReady(controller.player)

        press(Key.MediaPlayPause)
        compose.mainClock.advanceTimeBy(120_000)

        assertThat(controller.player.playWhenReady).isTrue()
        // The live edge: where the fake channel starts playing.
        assertThat(controller.player.currentPosition).isEqualTo(110_000)
        compose.onNodeWithText("Paused too long — back to live").assertExists()
    }

    @Test
    fun `with live pause off, play-pause offers to turn it on and the channel keeps playing`() {
        showChannel(livePause = false)
        hideControls()

        press(Key.MediaPlayPause)

        compose.onNodeWithText("Pause live TV?").assertExists()
        assertThat(controller.player.playWhenReady).isTrue()
        captureScreen("live_player_pause_prompt")
    }

    @Test
    fun `with live pause off, the bar's Pause button offers to turn it on too`() {
        showChannel(livePause = false)

        compose.onNodeWithText("Pause").performClick()

        compose.onNodeWithText("Pause live TV?").assertExists()
        assertThat(controller.player.playWhenReady).isTrue()
    }

    @Test
    fun `not now and back close the prompt with nothing changed, and the next press asks again`() {
        showChannel(livePause = false)
        press(Key.MediaPlayPause)

        compose.onNodeWithText("Not now").performClick()
        compose.onNodeWithText("Pause live TV?").assertDoesNotExist()

        press(Key.MediaPlayPause)
        compose.onNodeWithText("Pause live TV?").assertExists()
        Espresso.pressBack()
        compose.onNodeWithText("Pause live TV?").assertDoesNotExist()

        assertThat(controller.player.playWhenReady).isTrue()
        assertThat(turnedOn).isEqualTo(0)
        assertThat(dontAskAgain).isEqualTo(0)
    }

    @Test
    fun `don't ask again stops pause offering, until live pause is turned on`() {
        showChannel(livePause = false)
        press(Key.MediaPlayPause)

        compose.onNodeWithText("Don't ask again").performClick()
        compose.onNodeWithText("Pause live TV?").assertDoesNotExist()
        assertThat(dontAskAgain).isEqualTo(1)
        compose.onNodeWithText("Pause").assertDoesNotExist()

        press(Key.MediaPlayPause)
        compose.onNodeWithText("Pause live TV?").assertDoesNotExist()
        assertThat(controller.player.playWhenReady).isTrue()

        livePause = true
        compose.waitForIdle()
        compose.onNodeWithText("Pause").assertExists()
        press(Key.MediaPlayPause)
        assertThat(controller.player.playWhenReady).isFalse()
    }

    @Test
    fun `turn on and pause switches live pause on and pauses the channel once it's back`() {
        showChannel(livePause = false)
        press(Key.MediaPlayPause)
        compose.onNodeWithText("Turn on and pause").assertIsFocused()

        compose.onNodeWithText("Turn on and pause").performClick()
        assertThat(turnedOn).isEqualTo(1)

        // What PlayerScreen does when the setting turns on: a new player with the DVR buffer, on the
        // same channel.
        val rebuilt = players.controller(LIVE, dvr = true)
        livePause = true
        current = rebuilt
        compose.waitForIdle()
        players.play(rebuilt, isLive = true)
        compose.waitForIdle()

        assertThat(rebuilt.player.playWhenReady).isFalse()
        compose.onNodeWithText("Paused · 2:00 left").assertExists()
        rebuilt.release()
    }

    @Test
    fun `with live pause on, pause only pauses and play only resumes`() {
        showChannel()

        press(Key.MediaPause)
        press(Key.MediaPause)
        assertThat(controller.player.playWhenReady).isFalse()

        press(Key.MediaPlay)
        press(Key.MediaPlay)
        assertThat(controller.player.playWhenReady).isTrue()
    }

    @Test
    fun `holding play-pause pauses once rather than flickering`() {
        showChannel()

        compose.onRoot().performKeyInput {
            keyDown(Key.MediaPlayPause)
            advanceEventTime(HOLD_MILLIS)
            keyUp(Key.MediaPlayPause)
        }
        compose.waitForIdle()

        assertThat(controller.player.playWhenReady).isFalse()
    }

    @Test
    fun `the bar follows the player when something else resumes it`() {
        showChannel()
        press(Key.MediaPlayPause)

        // What the controller's automatic restart after a dropped connection does.
        controller.player.playWhenReady = true
        compose.waitForIdle()
        compose.onNodeWithText("Paused", substring = true).assertDoesNotExist()

        press(Key.MediaPlayPause)
        assertThat(controller.player.playWhenReady).isFalse()
    }

    @Test
    fun `typing a channel number tunes to it once the digits stop`() {
        showChannel(queue = CHANNELS)

        press(Key.One)
        press(Key.Two)
        compose.mainClock.advanceTimeBy(NUMBER_ENTRY_MILLIS)

        assertThat(played).containsExactly(12L)
    }

    @Test
    fun `channel up and down, and d-pad up and down, step through the channel list`() {
        showChannel(queue = CHANNELS, currentId = 11L)
        hideControls()

        press(Key.ChannelDown)
        press(Key.DirectionDown)
        press(Key.ChannelUp)
        press(Key.DirectionUp)

        assertThat(played).containsExactly(12L, 12L, 10L, 10L).inOrder()
    }

    private companion object {
        const val CONTROLS_TIMEOUT_MILLIS = 6_000L
        const val NUMBER_ENTRY_MILLIS = 2_500L
        const val HOLD_MILLIS = 1_000L

        // currentId never moves in these tests, so every zap steps from the channel the test started on.
        val CHANNELS = listOf(
            PlaybackQueue.Item(10L, "One", logoUrl = null, number = 1),
            PlaybackQueue.Item(11L, "Two", logoUrl = null, number = 2),
            PlaybackQueue.Item(12L, "Twelve", logoUrl = null, number = 12),
        )
    }
}
