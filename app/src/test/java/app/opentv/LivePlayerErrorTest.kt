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
import app.opentv.core.AppSettings
import app.opentv.data.repo.MediaKey
import app.opentv.data.repo.Playable
import app.opentv.player.PlaybackQueue
import app.opentv.player.PlayerController
import app.opentv.ui.player.CardChannel
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
 * A live channel that failed in a way trying again could fix: OK tries again, and the remote can still
 * zap away or leave (#370). The film player's side of this is [PlayerErrorPanelTest].
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = TV_1080P, application = android.app.Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@OptIn(ExperimentalTestApi::class, UnstableApi::class)
class LivePlayerErrorTest {

    @get:Rule
    val compose = createComposeRule()

    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()
    private val players = TestPlayers(context)
    private var controller: PlayerController? = null

    private var tries = 0
    private val zapped = mutableListOf<Int>()
    private var backs = 0

    @After
    fun release() {
        controller?.release()
        players.close()
    }

    /** A channel whose every tune fails in a way trying again could fix, counting each try. */
    private fun deadChannel() = players.controller(LIVE) { key ->
        tries++
        Playable.Failed(key, Playable.Failure.GONE, "Two")
    }

    private fun failed(retryable: Boolean) = (controller?.state?.value as? PlayerController.State.Error)
        ?.let { it.canRetry == retryable } == true

    private fun show(player: PlayerController, retryable: Boolean = true) {
        controller = player
        compose.setTvContent {
            LivePlayerContent(
                controller = player,
                settings = AppSettings.get(context),
                queue = CHANNELS,
                variants = emptyList(),
                currentId = 11L,
                previousId = null,
                onNow = { emptyMap() },
                cardChannel = CardChannel("#11", 2, "Two"),
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
        player.play(PlayerController.Request(MediaKey.Live(11)), debounce = false)
        players.until { failed(retryable) }
        compose.waitForIdle()
        compose.mainClock.advanceTimeBy(FOCUS_MILLIS)
    }

    private fun press(key: Key) {
        compose.onRoot().performKeyInput { pressKey(key) }
        compose.waitForIdle()
    }

    private fun untilFailedAgain(after: Int) = players.until { tries > after && failed(retryable = true) }

    @Test
    fun `Try again has the highlight`() {
        show(deadChannel())
        compose.mainClock.advanceTimeBy(CARD_TIMEOUT_MILLIS)

        compose.onNodeWithText("Try again").assertIsFocused()
        captureScreen("player_error_retry")
    }

    @Test
    fun `OK tries the channel again, without opening the controls`() {
        show(deadChannel())
        val before = tries

        for (ok in listOf(Key.DirectionCenter, Key.Enter, Key.NumPadEnter, Key.ButtonA)) {
            val was = tries
            press(ok)
            untilFailedAgain(after = was)
        }

        assertThat(tries).isEqualTo(before + 4)
        compose.onNodeWithContentDescription("Subtitles").assertDoesNotExist()
    }

    @Test
    fun `when trying again fails too, the highlight is back on Try again`() {
        show(deadChannel())
        val before = tries

        press(Key.DirectionCenter)
        untilFailedAgain(after = before)
        compose.waitForIdle()
        compose.mainClock.advanceTimeBy(FOCUS_MILLIS)

        compose.onNodeWithText("Try again").assertIsFocused()
    }

    @Test
    fun `holding OK tries once`() {
        show(deadChannel())
        val before = tries

        compose.onRoot().performKeyInput {
            keyDown(Key.DirectionCenter)
            advanceEventTime(HOLD_MILLIS)
            keyUp(Key.DirectionCenter)
        }
        compose.waitForIdle()
        players.advance(HOLD_MILLIS)

        assertThat(tries).isEqualTo(before + 1)
        compose.onNodeWithContentDescription("Subtitles").assertDoesNotExist()
    }

    @Test
    fun `up and down still change channel`() {
        show(deadChannel())

        press(Key.DirectionDown)
        press(Key.ChannelUp)

        assertThat(zapped).containsExactly(1, -1).inOrder()
    }

    @Test
    fun `one Back leaves the player`() {
        show(deadChannel())

        Espresso.pressBackUnconditionally()
        compose.waitForIdle()

        assertThat(backs).isEqualTo(1)
    }

    @Test
    fun `on an error trying again can't fix, there's no Try again and OK opens the controls`() {
        show(players.controller(LIVE, failing = 100), retryable = false)
        compose.onNodeWithText("Try again").assertDoesNotExist()

        press(Key.DirectionCenter)
        compose.mainClock.advanceTimeBy(FOCUS_MILLIS)

        compose.onNodeWithContentDescription("Subtitles").assertExists()
    }

    private companion object {
        const val FOCUS_MILLIS = 100L
        const val CARD_TIMEOUT_MILLIS = 5_000L
        const val HOLD_MILLIS = 1_000L

        val CHANNELS = listOf(
            PlaybackQueue.Item(10L, "One", logoUrl = null, number = 1),
            PlaybackQueue.Item(11L, "Two", logoUrl = null, number = 2),
            PlaybackQueue.Item(12L, "Twelve", logoUrl = null, number = 12),
        )
    }
}
