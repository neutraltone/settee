/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv

import androidx.compose.runtime.Composable
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import app.opentv.ui.player.LivePlayerControls
import app.opentv.ui.vod.VodPlayerControls
import com.google.common.truth.Truth.assertThat
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
class PlayerControlsTest {

    @get:Rule
    val compose = createComposeRule()

    private var left = false

    private val playPause = FocusRequester()

    // The players put focus on play/pause when the controls appear.
    private fun setVodControls() {
        compose.setTvContent { vodControls() }
        compose.runOnIdle { playPause.requestFocus() }
    }

    @Composable
    private fun vodControls() {
        VodPlayerControls(
            paused = false,
            onBack = { left = true },
            onRewind = {},
            onPlayPause = {},
            onForward = {},
            onSubtitles = {},
            onAudio = {},
            playPauseFocus = playPause,
        )
    }

    private fun setLiveControls(seekable: Boolean = false) {
        compose.setTvContent {
            LivePlayerControls(
                paused = false,
                onBack = { left = true },
                onPlayPause = {},
                playPauseFocus = playPause,
                seekable = seekable,
                hasQuality = true,
                canPopOut = true,
            )
        }
        compose.runOnIdle { playPause.requestFocus() }
    }

    private fun press(key: Key) = compose.onRoot().performKeyInput { pressKey(key) }

    @Test
    fun `the movie player's Back button leaves the player`() {
        setVodControls()

        compose.onNodeWithText("Back").performClick()

        assertThat(left).isTrue()
    }

    @Test
    fun `the movie player's Back button is two presses left of play and pause`() {
        setVodControls()
        compose.onNodeWithText("Pause").assertIsFocused()

        press(Key.DirectionLeft)
        press(Key.DirectionLeft)

        compose.onNodeWithText("Back").assertIsFocused()
        captureScreen("vod_player_controls_back_focused")
    }

    @Test
    fun `the live player's Back button leaves the player`() {
        setLiveControls()

        compose.onNodeWithText("Back").performClick()

        assertThat(left).isTrue()
    }

    @Test
    fun `the live player's Back button is left of play and pause`() {
        setLiveControls()
        compose.onNodeWithText("Pause").assertIsFocused()

        press(Key.DirectionLeft)

        compose.onNodeWithText("Back").assertIsFocused()
        captureScreen("player_controls_back_focused")
    }

    @Test
    fun `on a stream that can pause and rewind, Back is still the first button`() {
        setLiveControls(seekable = true)

        press(Key.DirectionLeft)
        press(Key.DirectionLeft)

        compose.onNodeWithText("Back").assertIsFocused()
    }
}
