/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv

import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertIsNotFocused
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.media3.common.util.UnstableApi
import app.opentv.core.SleepTimer
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

/**
 * A picker over the film and episode player keeps the remote to itself (#425): the control bar stays
 * composed underneath it, and an arrow off the picker's edge used to land on its hidden buttons.
 */
// Plain Application: OpenTvApp schedules WorkManager on start, which lone composables don't need.
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = TV_1080P, application = android.app.Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@OptIn(ExperimentalTestApi::class, UnstableApi::class)
class VodPlayerPickerTest {

    private val compose = createComposeRule()

    @get:Rule
    val testGraph = TestGraph(compose)

    private val app = testGraph.app
    private val players = TestPlayers(app)
    private val controller = players.controller()
    private val graph = testGraph.graph
    private val viewModel = testGraph.track(VodPlayerViewModel(app, graph))
    private var backs = 0

    @After
    fun release() {
        SleepTimer.clear()
        controller.release()
        players.close()
    }

    /** Part way into a film, its Subtitles picker opened from the bar, with the highlight on Off. */
    private fun openSubtitlesFromTheBar() {
        compose.setTvContent {
            VodPlayerContent(
                controller,
                viewModel,
                false,
                SubtitlePicks(onOff = {
                }, onPicked = {}),
                onBack = { backs++ },
            )
        }
        players.play(controller)
        controller.player.seekTo(TEN_MINUTES)
        players.untilReady(controller.player)
        settle()
        press(Key.DirectionDown)
        press(Key.DirectionCenter)
        settle()
        compose.onNodeWithText("Off").assertIsFocused()
    }

    private fun settle() {
        compose.mainClock.advanceTimeBy(SETTLE_MILLIS)
        compose.waitForIdle()
    }

    private fun press(key: Key) {
        compose.onRoot().performKeyInput { pressKey(key) }
        compose.waitForIdle()
    }

    @Test
    fun `Up from a picker's top row stays in the picker, so Select doesn't leave the player`() {
        openSubtitlesFromTheBar()

        press(Key.DirectionUp)

        compose.onNodeWithText("Off").assertIsFocused()
        compose.onNodeWithContentDescription("Close").assertIsNotFocused()
        press(Key.DirectionCenter)
        assertThat(backs).isEqualTo(0)
    }

    @Test
    fun `Down from a picker's last row stays in the picker`() {
        openSubtitlesFromTheBar()

        press(Key.DirectionDown)

        compose.onNodeWithText("Off").assertIsFocused()
        compose.onNodeWithContentDescription("Subtitles").assertIsNotFocused()
        compose.onNodeWithContentDescription("Audio").assertIsNotFocused()
    }

    @Test
    fun `Left and Right on a picker's row stay on it, and don't seek`() {
        openSubtitlesFromTheBar()

        press(Key.DirectionLeft)
        press(Key.DirectionRight)
        settle()

        compose.onNodeWithText("Off").assertIsFocused()
        assertThat(compose.seekBarIsFocused()).isFalse()
        assertThat(controller.player.currentPosition).isEqualTo(TEN_MINUTES)
        assertThat(backs).isEqualTo(0)
    }

    private companion object {
        const val TEN_MINUTES = 600_000L
        const val SETTLE_MILLIS = 600L
    }
}
