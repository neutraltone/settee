/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv

import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.media3.common.util.UnstableApi
import androidx.test.core.app.ApplicationProvider
import androidx.test.espresso.Espresso
import app.opentv.core.AppSettings
import app.opentv.player.PlaybackQueue
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

/**
 * One Back press is one step of the live player's Back rule — the channel list, then a picker, then
 * the bar, then out — whatever has the highlight (#427): Compose would otherwise spend a press on the
 * bar or the list taking the highlight off it.
 */
// Plain Application: OpenTvApp schedules WorkManager on start, which lone composables don't need.
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = TV_1080P, application = android.app.Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@OptIn(ExperimentalTestApi::class, UnstableApi::class)
class LivePlayerBackTest {

    @get:Rule
    val compose = createComposeRule()

    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()
    private val players = TestPlayers(context)
    private val controller = players.controller(LIVE, dvr = true)

    private val zapped = mutableListOf<Int>()
    private var backs = 0

    @After
    fun release() {
        controller.release()
        players.close()
    }

    private fun showPlayer() {
        compose.setTvContent {
            LivePlayerContent(
                controller = controller,
                settings = AppSettings.get(context),
                queue = CHANNELS,
                variants = emptyList(),
                currentId = 11L,
                previousId = null,
                onNow = { emptyMap() },
                cardChannel = null,
                nowAndNext = { null },
                recordingThis = false,
                livePause = true,
                pausePromptDismissed = false,
                onPlayChannel = {},
                onZap = { delta, _ -> zapped += delta },
                onTune = {},
                onToggleRecord = {},
                favourite = false,
                onToggleFavourite = {},
                onTurnOnLivePause = {},
                onDontAskAgain = {},
                subtitlePicks = SubtitlePicks(onOff = {}, onPicked = {}),
                onBack = { backs++ },
            )
        }
        players.play(controller, isLive = true)
        compose.waitForIdle()
    }

    private fun press(key: Key) {
        compose.onRoot().performKeyInput { pressKey(key) }
        compose.waitForIdle()
    }

    /** OK, and the beat the bar takes to fade in and take the highlight. */
    private fun openControls() {
        press(Key.DirectionCenter)
        compose.mainClock.advanceTimeBy(FOCUS_MILLIS)
        assertThat(compose.seekBarIsFocused()).isTrue()
    }

    private fun back() {
        Espresso.pressBack()
        compose.mainClock.advanceTimeBy(FOCUS_MILLIS)
    }

    @Test
    fun `one Back hides the controls and stays in the player`() {
        showPlayer()
        openControls()

        back()

        compose.onNodeWithContentDescription("Pause").assertDoesNotExist()
        assertThat(backs).isEqualTo(0)
    }

    @Test
    fun `one Back hides the controls of a paused channel and stays in the player`() {
        showPlayer()
        press(Key.MediaPause)
        compose.mainClock.advanceTimeBy(FOCUS_MILLIS)
        compose.onNodeWithText("Paused · 2:00 left").assertExists()

        back()

        compose.onNodeWithContentDescription("Play").assertDoesNotExist()
        assertThat(backs).isEqualTo(0)
    }

    @Test
    fun `after Back hides the controls, up and down change channel and OK brings them back`() {
        showPlayer()
        openControls()
        back()

        press(Key.DirectionUp)
        press(Key.DirectionDown)
        assertThat(zapped).containsExactly(-1, 1).inOrder()

        openControls()
    }

    @Test
    fun `one Back on a picker closes only the picker, leaving the bar up with its highlight`() {
        showPlayer()
        openControls()
        press(Key.DirectionDown)
        press(Key.DirectionCenter)
        compose.mainClock.advanceTimeBy(FOCUS_MILLIS)
        compose.onNodeWithText("Off").assertExists()

        back()

        compose.onNodeWithText("Off").assertDoesNotExist()
        assertThat(compose.seekBarIsFocused()).isTrue()
        assertThat(backs).isEqualTo(0)
    }

    @Test
    fun `one Back closes the channel list and stays in the player`() {
        showPlayer()
        press(Key.DirectionLeft)
        compose.mainClock.advanceTimeBy(FOCUS_MILLIS)
        compose.onNodeWithText("Channels").assertExists()

        back()

        compose.onNodeWithText("Channels").assertDoesNotExist()
        assertThat(backs).isEqualTo(0)
    }

    @Test
    fun `with nothing showing, one Back leaves the player`() {
        showPlayer()

        Espresso.pressBackUnconditionally()
        compose.waitForIdle()

        assertThat(backs).isEqualTo(1)
    }

    private companion object {
        const val FOCUS_MILLIS = 100L
        val CHANNELS = listOf(
            PlaybackQueue.Item(10L, "One", logoUrl = null, number = 1),
            PlaybackQueue.Item(11L, "Two", logoUrl = null, number = 2),
            PlaybackQueue.Item(12L, "Twelve", logoUrl = null, number = 12),
        )
    }
}
