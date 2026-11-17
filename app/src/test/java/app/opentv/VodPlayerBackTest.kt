/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv

import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.media3.common.util.UnstableApi
import androidx.test.core.app.ApplicationProvider
import androidx.test.espresso.Espresso
import app.opentv.core.ServiceLocator
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
 * One Back press is one step of the film and episode player's Back rule, whatever has the highlight
 * (#427): Compose would otherwise spend a press on the bar taking the highlight off it.
 */
// Plain Application: OpenTvApp schedules WorkManager on start, which lone composables don't need.
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = TV_1080P, application = android.app.Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@OptIn(ExperimentalTestApi::class, UnstableApi::class)
class VodPlayerBackTest {

    @get:Rule
    val compose = createComposeRule()

    private val app: android.app.Application = ApplicationProvider.getApplicationContext()
    private val players = TestPlayers(app)
    private val controller = players.controller()
    private val graph = ServiceLocator.Graph(app)
    private val viewModel = VodPlayerViewModel(app, graph)
    private var backs = 0

    @After
    fun release() {
        SleepTimer.clear()
        controller.release()
        players.close()
        graph.database.close()
    }

    /** A film playing, with the bar it opens with up and holding the highlight. */
    private fun showFilm() {
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
        settle()
        assertThat(compose.seekBarIsFocused()).isTrue()
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
    fun `one Back hides the bar of a playing film and stays in the player`() {
        showFilm()

        Espresso.pressBack()
        settle()

        compose.onNode(seekBar).assertDoesNotExist()
        assertThat(backs).isEqualTo(0)
    }

    @Test
    fun `Back held while the bar times out still only hides it`() {
        showFilm()

        compose.onRoot().performKeyInput { keyDown(Key.Back) }
        compose.mainClock.advanceTimeBy(CONTROLS_TIMEOUT_MILLIS)
        compose.onRoot().performKeyInput { keyUp(Key.Back) }
        settle()

        compose.onNode(seekBar).assertDoesNotExist()
        assertThat(backs).isEqualTo(0)
    }

    @Test
    fun `Back on the bar of a paused film hides it first, and a second Back leaves`() {
        showFilm()
        press(Key.MediaPause)
        settle()

        Espresso.pressBack()
        settle()

        compose.onNode(seekBar).assertDoesNotExist()
        assertThat(backs).isEqualTo(0)

        Espresso.pressBackUnconditionally()
        settle()

        assertThat(backs).isEqualTo(1)
    }

    @Test
    fun `one Back on a picker closes only the picker, leaving the bar up with its highlight`() {
        showFilm()
        press(Key.DirectionDown)
        press(Key.DirectionCenter)
        settle()
        compose.onNodeWithText("Off").assertExists()

        Espresso.pressBack()
        settle()

        compose.onNodeWithText("Off").assertDoesNotExist()
        // Back on the button that opened it (#452).
        compose.onNodeWithContentDescription("Subtitles").assertIsFocused()
        assertThat(backs).isEqualTo(0)
    }

    @Test
    fun `with nothing showing, one Back leaves the player`() {
        showFilm()
        compose.mainClock.advanceTimeBy(CONTROLS_TIMEOUT_MILLIS)
        compose.onNodeWithContentDescription("Pause").assertDoesNotExist()

        Espresso.pressBackUnconditionally()
        compose.waitForIdle()

        assertThat(backs).isEqualTo(1)
    }

    private companion object {
        const val CONTROLS_TIMEOUT_MILLIS = 6_000L
        const val SETTLE_MILLIS = 600L
    }
}
