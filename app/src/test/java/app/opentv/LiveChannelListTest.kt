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
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.hasAnyDescendant
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isFocused
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.media3.common.util.UnstableApi
import androidx.test.core.app.ApplicationProvider
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

/** The live player's channel list: opening it, moving through it, and what its rows say. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = TV_1080P, application = android.app.Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@OptIn(ExperimentalTestApi::class, UnstableApi::class)
class LiveChannelListTest {

    @get:Rule
    val compose = createComposeRule()

    @get:Rule
    val artwork = PlaceholderArtwork()

    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()
    private val players = TestPlayers(context)
    private val controller = players.controller(LIVE, dvr = true)

    private val played = mutableListOf<Long>()
    private val zapped = mutableListOf<Int>()
    private var previous by mutableStateOf<Long?>(null)
    private var onNow by mutableStateOf(emptyMap<Long, String>())

    @After
    fun release() {
        controller.release()
        players.close()
    }

    private fun showPlayer(current: Long) {
        compose.setTvContent {
            LivePlayerContent(
                controller = controller,
                settings = AppSettings.get(context),
                queue = CHANNELS,
                variants = emptyList(),
                currentId = current,
                previousId = previous,
                onNow = { onNow },
                bannerChannel = null,
                nowAndNext = { null },
                recordingThis = false,
                livePause = true,
                pausePromptDismissed = false,
                onPlayChannel = { played += it },
                onZap = { delta, _ -> zapped += delta },
                onTune = {},
                onToggleRecord = {},
                favourite = false,
                onToggleFavourite = {},
                onTurnOnLivePause = {},
                onDontAskAgain = {},
                subtitlePicks = SubtitlePicks(onOff = {}, onPicked = {}),
                onBack = {},
            )
        }
        players.play(controller, isLive = true)
        compose.waitForIdle()
    }

    private fun press(key: Key) {
        compose.onRoot().performKeyInput { pressKey(key) }
        compose.waitForIdle()
    }

    @Test
    fun `Left opens the channel list in one press while the controls are hidden, on the channel that's on`() {
        showPlayer(current = 11L)

        press(Key.DirectionLeft)
        compose.mainClock.advanceTimeBy(FOCUS_MILLIS)

        compose.onNodeWithText("Channels").assertExists()
        compose.onNode(isFocused() and hasAnyDescendant(hasText("Two")), useUnmergedTree = true).assertExists()
    }

    @Test
    fun `Left never opens the channel list while the controls are up`() {
        showPlayer(current = 11L)
        press(Key.DirectionCenter)
        compose.mainClock.advanceTimeBy(FOCUS_MILLIS)

        press(Key.DirectionLeft)
        press(Key.DirectionDown)
        press(Key.DirectionLeft)
        compose.mainClock.advanceTimeBy(FOCUS_MILLIS)

        compose.onNodeWithText("Channels").assertDoesNotExist()
    }

    @Test
    fun `moving through the channel list doesn't change channel, and OK does`() {
        showPlayer(current = 10L)
        press(Key.DirectionLeft)
        compose.mainClock.advanceTimeBy(FOCUS_MILLIS)

        press(Key.DirectionDown)
        press(Key.DirectionDown)
        assertThat(played).isEmpty()
        assertThat(zapped).isEmpty()

        press(Key.DirectionCenter)
        assertThat(played).containsExactly(12L)
    }

    @Test
    fun `the channel list shows what's on each channel, and nothing for one without a guide`() {
        onNow = mapOf(10L to "Breakfast", 12L to "Darts")
        showPlayer(current = 11L)

        press(Key.DirectionLeft)
        compose.mainClock.advanceTimeBy(FOCUS_MILLIS)

        compose.onNodeWithText("Breakfast").assertExists()
        compose.onNodeWithText("Darts").assertExists()
        compose.onNodeWithText("Two").assertExists()
    }

    @Test
    fun `the channel last watched is pinned at the top of the channel list`() {
        onNow = mapOf(12L to "Darts")
        previous = 12L
        showPlayer(current = 11L)

        press(Key.DirectionLeft)
        compose.mainClock.advanceTimeBy(FOCUS_MILLIS)

        compose.onNodeWithText("Last").assertExists()
        compose.onAllNodesWithText("Twelve").assertCountEquals(2)
        captureScreen("live_player_channel_list")
    }

    private companion object {
        const val FOCUS_MILLIS = 100L
        val CHANNELS = listOf(
            PlaybackQueue.Item(10L, "One", logoUrl = "https://example.com/logo/10.png", number = 1),
            PlaybackQueue.Item(11L, "Two", logoUrl = "https://example.com/logo/11.png", number = 2),
            PlaybackQueue.Item(12L, "Twelve", logoUrl = "https://example.com/logo/12.png", number = 12),
        )
    }
}
