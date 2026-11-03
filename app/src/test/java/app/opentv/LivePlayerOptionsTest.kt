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
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isDialog
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
import app.opentv.core.PipState
import app.opentv.data.model.Channel
import app.opentv.player.PlaybackQueue
import app.opentv.ui.player.BannerChannel
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

/** The live player's options list, opened by Menu or the bar's Options button. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = TV_1080P, application = android.app.Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@OptIn(ExperimentalTestApi::class, UnstableApi::class)
class LivePlayerOptionsTest {

    @get:Rule
    val compose = createComposeRule()

    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()
    private val players = TestPlayers(context)
    private val controller = players.controller(LIVE, dvr = true)

    private val zapped = mutableListOf<Int>()
    private var backs = 0
    private var favouriteToggles = 0
    private var recordToggles = 0
    private var variants by mutableStateOf(emptyList<Channel>())
    private var favourite by mutableStateOf(false)
    private var recording by mutableStateOf(false)

    @After
    fun release() {
        controller.release()
        players.close()
    }

    private fun showChannel(queue: List<PlaybackQueue.Item> = emptyList(), currentId: Long = 1L) {
        compose.setTvContent {
            LivePlayerContent(
                controller = controller,
                settings = AppSettings.get(context),
                queue = queue,
                variants = variants,
                currentId = currentId,
                previousId = null,
                bannerChannel = queue.firstOrNull { currentId in it.qualityIds }?.let {
                    BannerChannel("#${it.id}", it.number, it.name)
                },
                programmeOnNow = { null },
                recordingThis = recording,
                livePause = true,
                pausePromptDismissed = false,
                onPlayChannel = {},
                onZap = { delta, _ -> zapped += delta },
                onTune = {},
                onToggleRecord = { recordToggles++ },
                favourite = favourite,
                onToggleFavourite = { favouriteToggles++ },
                onTurnOnLivePause = {},
                onDontAskAgain = {},
                subtitlePicks = SubtitlePicks(onOff = {}, onPicked = {}),
                onBack = { backs++ },
            )
        }
        players.play(controller, isLive = true)
        compose.waitForIdle()
    }

    private fun row(id: Long, quality: String) = Channel(
        id = id,
        sourceId = 1,
        streamId = "$id",
        name = "Two $quality",
        categoryId = null,
        logoUrl = null,
        epgChannelId = null,
        number = null,
        streamUrl = "http://example.com/live/u/p/$id.ts",
        qualityLabel = quality,
    )

    private fun hideBanner() = compose.mainClock.advanceTimeBy(BANNER_TIMEOUT_MILLIS)

    private fun press(key: Key) {
        compose.onRoot().performKeyInput { pressKey(key) }
        compose.waitForIdle()
    }

    private fun inList(text: String) = compose.onNode(hasText(text) and hasAnyAncestor(isDialog()))

    private fun closeList() {
        Espresso.pressBack()
        compose.waitForIdle()
    }

    @Test
    fun `Menu opens the options list over the channel, with what applies to it`() {
        showChannel()

        press(Key.Menu)

        inList("Subtitles").assertIsFocused()
        inList("Audio").assertExists()
        inList("Aspect ratio").assertExists()
        inList("Sleep timer").assertExists()
        inList("Add to Favourites").assertExists()
        inList("● Record now").assertExists()
        inList("Quality").assertDoesNotExist()
        captureScreen("live_player_options")
    }

    @Test
    fun `Back closes the list with the bar hidden, and the remote still drives the player`() {
        showChannel(queue = CHANNELS, currentId = 11L)
        hideBanner()
        press(Key.Menu)

        closeList()

        compose.onNodeWithText("Sleep timer").assertDoesNotExist()
        assertThat(backs).isEqualTo(0)
        press(Key.DirectionUp)
        assertThat(zapped).containsExactly(-1)
    }

    @Test
    fun `Back returns the highlight to the bar's button, which hasn't timed out meanwhile`() {
        showChannel()
        press(Key.DirectionCenter)
        compose.mainClock.advanceTimeBy(FOCUS_MILLIS)
        compose.onNodeWithText("Pause").assertIsFocused()
        press(Key.Menu)

        compose.mainClock.advanceTimeBy(CONTROLS_TIMEOUT_MILLIS)
        inList("Sleep timer").assertExists()
        closeList()

        compose.onNodeWithText("Pause").assertIsFocused()
    }

    @Test
    fun `the bar's Options button opens the same list`() {
        showChannel()
        press(Key.DirectionCenter)

        compose.onNodeWithText("Options").performClick()

        inList("Sleep timer").assertExists()
    }

    @Test
    fun `Quality is offered with more than one, and opens the picker, which stays up`() {
        variants = listOf(row(11L, "HD"), row(12L, "FHD"))
        showChannel(queue = CHANNELS, currentId = 11L)
        press(Key.Menu)

        inList("Quality").performClick()
        compose.mainClock.advanceTimeBy(CONTROLS_TIMEOUT_MILLIS)

        compose.onNodeWithText("FHD").assertExists()
    }

    @Test
    fun `Subtitles opens the bar's picker, which stays up`() {
        showChannel()
        press(Key.Menu)

        inList("Subtitles").performClick()
        compose.mainClock.advanceTimeBy(CONTROLS_TIMEOUT_MILLIS)

        compose.onNodeWithText("Off").assertExists()
    }

    @Test
    fun `Aspect ratio opens the bar's picker`() {
        showChannel()
        press(Key.Menu)

        inList("Aspect ratio").performClick()

        compose.onNodeWithText("Stretch").assertExists()
    }

    /** Select on a row of the list, as a remote does it: a touch would leave nothing able to take focus. */
    private fun chooseByRemote(text: String) {
        inList(text).performKeyInput { pressKey(Key.DirectionCenter) }
        compose.mainClock.advanceTimeBy(FOCUS_MILLIS)
        compose.waitForIdle()
    }

    @Test
    fun `a picker chosen from the list takes the highlight, so the remote can use it`() {
        showChannel()
        press(Key.Menu)

        chooseByRemote("Subtitles")

        compose.onNodeWithText("Off").assertIsFocused()
    }

    @Test
    fun `Favourite and Record act on the channel and say what they'll do`() {
        showChannel()
        press(Key.Menu)
        inList("Add to Favourites").performClick()
        assertThat(favouriteToggles).isEqualTo(1)

        press(Key.Menu)
        inList("● Record now").performClick()
        assertThat(recordToggles).isEqualTo(1)

        favourite = true
        recording = true
        press(Key.Menu)
        inList("Remove from Favourites").assertExists()
        inList("■ Stop recording").assertExists()
    }

    @Test
    fun `Menu doesn't open the list over the channel list`() {
        showChannel(queue = CHANNELS, currentId = 11L)
        press(Key.DirectionLeft)
        compose.mainClock.advanceTimeBy(FOCUS_MILLIS)

        press(Key.Menu)

        compose.onNodeWithText("Sleep timer").assertDoesNotExist()
    }

    @Test
    fun `Menu doesn't open the list over a picker`() {
        showChannel()
        press(Key.DirectionCenter)
        compose.onNodeWithText("Aspect").performClick()

        press(Key.Menu)

        compose.onNodeWithText("Sleep timer").assertDoesNotExist()
    }

    @Test
    fun `the list doesn't open in picture-in-picture`() {
        PipState.setInPip(true)
        try {
            showChannel()

            press(Key.Menu)

            compose.onNodeWithText("Sleep timer").assertDoesNotExist()
        } finally {
            PipState.setInPip(false)
        }
    }

    private companion object {
        const val FOCUS_MILLIS = 100L
        const val BANNER_TIMEOUT_MILLIS = 5_000L
        const val CONTROLS_TIMEOUT_MILLIS = 6_000L

        val CHANNELS = listOf(
            PlaybackQueue.Item(10L, "One", logoUrl = null, number = 1),
            PlaybackQueue.Item(11L, "Two", logoUrl = null, number = 2),
        )
    }
}
