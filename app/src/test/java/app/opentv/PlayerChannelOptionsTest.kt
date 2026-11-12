/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv

import androidx.compose.runtime.CompositionLocalProvider
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
import androidx.compose.ui.test.requestFocus
import androidx.media3.common.util.UnstableApi
import androidx.test.core.app.ApplicationProvider
import app.opentv.core.AppSettings
import app.opentv.player.PlaybackQueue
import app.opentv.ui.options.LocalConfirmLive
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

/** The channel options from the player's channel list, the only place the player offers them here. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = TV_1080P, application = android.app.Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@OptIn(ExperimentalTestApi::class, UnstableApi::class)
class PlayerChannelOptionsTest {

    @get:Rule
    val compose = createComposeRule()

    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()
    private val players = TestPlayers(context)
    private val controller = players.controller(LIVE, dvr = true)

    private val played = mutableListOf<Long>()
    private val optionsOpenedOn = mutableListOf<String>()
    private var watchFromOptions: (() -> Unit)? = null

    private var playing by mutableStateOf(TWO)
    private var previous by mutableStateOf<Long?>(null)
    private var queue by mutableStateOf(CHANNELS)

    /** Stands in for the check before going live. */
    private var confirmLive by mutableStateOf<(() -> Unit) -> Unit>({ start -> start() })

    @After
    fun release() {
        controller.release()
        players.close()
    }

    private fun showPlayer() {
        compose.setTvContent {
            CompositionLocalProvider(LocalConfirmLive provides confirmLive) {
                LivePlayerContent(
                    controller = controller,
                    settings = AppSettings.get(context),
                    queue = queue,
                    variants = emptyList(),
                    currentId = playing,
                    previousId = previous,
                    cardChannel = null,
                    nowAndNext = { null },
                    onNow = { emptyMap() },
                    recordingThis = false,
                    livePause = true,
                    pausePromptDismissed = false,
                    onPlayChannel = { played += it },
                    onZap = { _, _ -> },
                    onTune = {},
                    onToggleRecord = {},
                    onTurnOnLivePause = {},
                    onDontAskAgain = {},
                    subtitlePicks = SubtitlePicks(onOff = {}, onPicked = {}),
                    onBack = {},
                    favourite = false,
                    onToggleFavourite = {},
                    onChannelOptions = { item, onWatch ->
                        optionsOpenedOn += item.name
                        watchFromOptions = onWatch
                    },
                )
            }
        }
        players.play(controller, isLive = true)
        compose.waitForIdle()
    }

    private fun press(key: Key) {
        compose.onRoot().performKeyInput { pressKey(key) }
        compose.waitForIdle()
    }

    private fun openChannelList() {
        press(Key.DirectionLeft)
        compose.mainClock.advanceTimeBy(FOCUS_MILLIS)
    }

    @Test
    fun `Menu with the channel list closed opens no channel options`() {
        showPlayer()

        press(Key.Menu)

        assertThat(optionsOpenedOn).isEmpty()
    }

    @Test
    fun `Menu with the channel list showing opens the highlighted channel's options`() {
        showPlayer()
        openChannelList()

        press(Key.Menu)

        assertThat(optionsOpenedOn).containsExactly("Two")
        compose.onNodeWithText("Subtitles").assertDoesNotExist()
    }

    @Test
    fun `holding Select on the channel list opens the channel's options, without switching to it`() {
        showPlayer()
        openChannelList()

        compose.onRoot().performKeyInput {
            keyDown(Key.DirectionCenter)
            advanceEventTime(1_000)
            keyUp(Key.DirectionCenter)
        }
        compose.waitForIdle()

        assertThat(optionsOpenedOn).containsExactly("Two")
        assertThat(played).isEmpty()
    }

    @Test
    fun `Menu on the Last row opens that channel's options`() {
        previous = TWELVE
        showPlayer()
        openChannelList()

        compose.onNodeWithText("Last").requestFocus()
        press(Key.Menu)

        assertThat(optionsOpenedOn).containsExactly("Twelve")
    }

    @Test
    fun `Watch from a channel's options switches this player to it and closes the list`() {
        showPlayer()
        openChannelList()
        press(Key.Menu)

        compose.runOnIdle { watchFromOptions!!() }
        compose.mainClock.advanceTimeBy(FOCUS_MILLIS)

        assertThat(played).containsExactly(TWO)
        compose.onNodeWithText("Channels").assertDoesNotExist()
    }

    @Test
    fun `picking from the channel list goes through the check before going live`() {
        var asked: (() -> Unit)? = null
        confirmLive = { start -> asked = start }
        showPlayer()
        openChannelList()

        compose.onNodeWithText("Twelve").performClick()

        assertThat(played).isEmpty()
        compose.onNodeWithText("Channels").assertExists()

        compose.runOnIdle { asked!!() }

        assertThat(played).containsExactly(TWELVE)
    }

    @Test
    fun `picking the channel already on needs no check, as it opens no new stream`() {
        confirmLive = { _ -> error("Nothing should need confirming") }
        showPlayer()
        openChannelList()

        compose.onNodeWithText("Two").performClick()

        assertThat(played).containsExactly(TWO)
    }

    @Test
    fun `when the highlighted channel leaves the list it moves to a neighbour, and the channel keeps playing`() {
        showPlayer()
        openChannelList()

        queue = CHANNELS.filterNot { TWO in it.qualityIds }
        compose.waitForIdle()

        compose.onNodeWithText("Twelve").assertIsFocused()
        assertThat(played).isEmpty()
    }

    private companion object {
        const val FOCUS_MILLIS = 100L
        const val TWO = 11L
        const val TWELVE = 12L
        val CHANNELS = listOf(
            PlaybackQueue.Item(10L, "One", null, 1, setOf(10L)),
            PlaybackQueue.Item(TWO, "Two", null, 2, setOf(TWO)),
            PlaybackQueue.Item(TWELVE, "Twelve", null, 12, setOf(TWELVE)),
        )
    }
}
