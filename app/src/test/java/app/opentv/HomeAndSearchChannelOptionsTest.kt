/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv

import android.app.Application
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.requestFocus
import app.opentv.data.model.Channel
import app.opentv.data.model.Recording
import app.opentv.data.model.RecordingStatus
import app.opentv.data.repo.LineupChannel
import app.opentv.data.repo.LivePreview
import app.opentv.ui.ChannelsViewModel
import app.opentv.ui.HomeChannel
import app.opentv.ui.HomeContent
import app.opentv.ui.LiveNow
import app.opentv.ui.LiveTile
import app.opentv.ui.channels.SearchContent
import app.opentv.ui.options.ConfirmLiveHost
import app.opentv.ui.options.ItemOptionsHost
import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** The channel options panel from Home's Favourite Channels row and from search results. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = TV_1080P, application = Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class HomeAndSearchChannelOptionsTest {

    @get:Rule
    val compose = createComposeRule()

    @get:Rule
    val artwork = PlaceholderArtwork()

    /** What happened, in order: "stop preview", "play <name>", "saved <search>". */
    private val events = mutableListOf<String>()

    private fun lineup(id: Long, name: String): LineupChannel {
        val channel = Channel(
            id = id,
            sourceId = 1,
            streamId = "$id",
            name = name,
            streamUrl = "https://example.com/$id.ts",
            categoryId = null,
            logoUrl = "https://example.com/$id.png",
            epgChannelId = null,
            number = null,
        )
        return LineupChannel(channel, listOf(channel))
    }

    private val running = Recording(
        id = 1,
        channelId = 99,
        sourceId = 1,
        channelName = "ITV",
        title = "The Chase",
        filePath = "/tmp/1.ts",
        streamUrl = "https://example.com/99.ts",
        status = RecordingStatus.RECORDING,
    )

    private var favourites by mutableStateOf(listOf(lineup(1, "BBC One"), lineup(2, "BBC Two"), lineup(3, "ITV")))

    private var results by mutableStateOf(
        listOf(lineup(1, "BBC One"), lineup(2, "BBC Two"), lineup(3, "BBC Three")).map { ChannelsViewModel.Row(it) },
    )

    private fun homeContent() {
        compose.setTvContent {
            ItemOptionsHost { HomeBody() }
        }
    }

    @androidx.compose.runtime.Composable
    private fun HomeBody() {
        HomeContent(
            continueWatching = emptyList(),
            favourites = emptyList(),
            favouriteChannels = favourites.map { HomeChannel(it) },
            onResume = {},
            onOpenSeries = {},
            onOpenMovie = {},
            onPlayChannel = { events += "play ${it.name}" },
            takeFocus = true,
            stopPreview = { events += "stop preview" },
        )
    }

    private lateinit var view: android.view.View
    private val device = DeviceKeyboard(compose) { view }

    private fun searchContent() {
        compose.setTvContent {
            view = androidx.compose.ui.platform.LocalView.current
            ItemOptionsHost { SearchBody() }
        }
    }

    @androidx.compose.runtime.Composable
    private fun SearchBody() {
        SearchContent(
            query = androidx.compose.runtime.remember { TextFieldState("bbc") },
            channelResults = results,
            movieResults = emptyList(),
            seriesResults = emptyList(),
            onPlayChannel = { events += "play ${it.name}" },
            onPlayMovie = {},
            onOpenSeries = {},
            onBack = {},
            onResultOpened = { events += "saved $it" },
        )
    }

    private fun waitFor(text: String): SemanticsNodeInteraction {
        compose.waitUntil(TIMEOUT_MILLIS) {
            compose.onAllNodes(hasText(text, substring = true)).fetchSemanticsNodes().isNotEmpty()
        }
        return compose.onNode(hasText(text, substring = true))
    }

    private fun assertChannelActionsShown() {
        waitFor("Watch").assertIsFocused()
        waitFor("Add to Favourites")
        waitFor("Hide channel")
        waitFor("Open in external player")
        waitFor("Record now")
    }

    private fun holdSelectOn(name: String) {
        compose.onNodeWithText(name).requestFocus().performKeyInput {
            keyDown(Key.DirectionCenter)
            advanceEventTime(1_000)
            keyUp(Key.DirectionCenter)
        }
    }

    private fun pressMenuOn(name: String) {
        compose.onNodeWithText(name).requestFocus().performKeyInput { pressKey(Key.Menu) }
    }

    @Test
    fun `Menu on a Favourite Channel on Home opens its options`() {
        homeContent()

        pressMenuOn("BBC One")

        assertChannelActionsShown()
        captureScreen("home_channel_options")
    }

    @Test
    fun `holding Select on a Favourite Channel on Home opens its options, without playing it`() {
        homeContent()

        holdSelectOn("BBC One")

        assertChannelActionsShown()
        assertThat(events).isEmpty()
    }

    @Test
    fun `Watch on Home stops the live preview before the player starts`() {
        homeContent()
        pressMenuOn("BBC Two")

        waitFor("Watch").performClick()
        compose.waitForIdle()

        assertThat(events).containsExactly("stop preview", "play BBC Two").inOrder()
    }

    @Test
    fun `with a recording running, Select on Home asks first and leaves the preview alone`() {
        compose.setTvContent {
            ConfirmLiveHost(listOf(running), onStopRecording = {}) { HomeBody() }
        }

        compose.onNodeWithText("BBC One").requestFocus().performKeyInput { pressKey(Key.DirectionCenter) }

        waitFor("Recording in progress")
        assertThat(events).isEmpty()
    }

    @Test
    fun `keeping the recording from the Live Preview tile leaves the preview alone`() {
        val channel = lineup(4, "Channel 4").primary
        val tile = LiveTile(LiveNow(LivePreview.Playable(channel), "Countdown", "The News"), null, null)
        compose.setTvContent {
            ConfirmLiveHost(listOf(running), onStopRecording = {}) {
                HomeContent(
                    continueWatching = emptyList(),
                    favourites = emptyList(),
                    favouriteChannels = emptyList(),
                    onResume = {},
                    onOpenSeries = {},
                    onOpenMovie = {},
                    onPlayChannel = {},
                    takeFocus = true,
                    live = tile,
                    onWatchLive = { events += "play ${it.name}" },
                    stopPreview = { events += "stop preview" },
                )
            }
        }

        compose.onNodeWithText("Channel 4").performClick()
        waitFor("Keep recording").performClick()
        compose.waitForIdle()

        assertThat(events).isEmpty()
    }

    @Test
    fun `when the last result leaves, with no Recent Searches, the highlight goes to the search box`() {
        results = results.take(1)
        searchContent()
        compose.onNodeWithText("BBC One").requestFocus()

        results = emptyList()
        compose.waitForIdle()

        assertThat(device.highlighted()).isTrue()
    }

    @Test
    fun `when the highlighted channel leaves Home's row, the highlight moves to its neighbour`() {
        homeContent()
        compose.onNodeWithText("BBC Two").requestFocus()

        favourites = favourites.filterNot { it.primary.name == "BBC Two" }
        compose.waitForIdle()

        compose.onNodeWithText("ITV").assertIsFocused()
    }

    @Test
    fun `Menu on a channel in search results opens its options`() {
        searchContent()

        pressMenuOn("BBC One")

        assertChannelActionsShown()
        captureScreen("search_channel_options")
    }

    @Test
    fun `holding Select on a channel in search results opens its options, without playing it`() {
        searchContent()

        holdSelectOn("BBC One")

        assertChannelActionsShown()
        assertThat(events).isEmpty()
    }

    @Test
    fun `Watch from search results plays the channel and saves the search`() {
        searchContent()
        pressMenuOn("BBC Two")

        waitFor("Watch").performClick()
        compose.waitForIdle()

        assertThat(events).containsExactly("saved bbc", "play BBC Two").inOrder()
    }

    @Test
    fun `with a recording running, Select in search results asks first and saves nothing yet`() {
        compose.setTvContent {
            ConfirmLiveHost(listOf(running), onStopRecording = {}) { SearchBody() }
        }

        compose.onNodeWithText("BBC One").requestFocus().performKeyInput { pressKey(Key.DirectionCenter) }

        waitFor("Recording in progress")
        assertThat(events).isEmpty()
    }

    @Test
    fun `when the highlighted channel leaves the results, the highlight moves to its neighbour`() {
        searchContent()
        compose.onNodeWithText("BBC Two").requestFocus()

        results = results.filterNot { it.primary.name == "BBC Two" }
        compose.waitForIdle()

        compose.onNodeWithText("BBC Three").assertIsFocused()
    }

    private companion object {
        const val TIMEOUT_MILLIS = 10_000L
    }
}
