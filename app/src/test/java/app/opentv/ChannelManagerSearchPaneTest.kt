/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv

import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.text.input.setTextAndPlaceCursorAtEnd
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.pressKey
import app.opentv.data.model.Channel
import app.opentv.data.repo.LineupChannel
import app.opentv.ui.ChannelsViewModel
import app.opentv.ui.channels.ChannelManagerSearchPane
import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

// Plain Application: OpenTvApp schedules WorkManager on start, which a lone composable doesn't need.
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = TV_1080P, application = android.app.Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@OptIn(ExperimentalTestApi::class)
class ChannelManagerSearchPaneTest {

    @get:Rule
    val compose = createComposeRule()

    @get:Rule
    val artwork = PlaceholderArtwork()

    private val query = TextFieldState()

    private var leftToRail = false

    private fun show(results: List<ChannelsViewModel.Row> = emptyList()) {
        compose.setTvContent {
            ChannelManagerSearchPane(
                query = query,
                results = results,
                onToggleHidden = { _, _ -> },
                onToggleFavourite = {},
                onExitLeft = {
                    leftToRail = true
                    true
                },
            )
        }
        compose.waitForIdle()
    }

    private val searchBox get() = compose.onNode(hasSetTextAction())

    @Test
    fun `what the device keyboard types becomes the search`() {
        show()
        searchBox.assertIsFocused()

        searchBox.performTextInput("news")

        assertThat(query.text.toString()).isEqualTo("news")
        captureScreen("channel_manager_search")
    }

    @Test
    fun `Down from the search box moves the highlight onto the first match`() {
        query.setTextAndPlaceCursorAtEnd("news")
        show(results = listOf(channelRow("Sky News"), channelRow("BBC News")))

        searchBox.performKeyInput { pressKey(Key.DirectionDown) }

        compose.onAllNodesWithContentDescription("Favourite")[0].assertIsFocused()
        captureScreen("channel_manager_search_results")
    }

    @Test
    fun `Left from the start of the search box goes back to the categories`() {
        show()

        searchBox.performKeyInput { pressKey(Key.DirectionLeft) }

        assertThat(leftToRail).isTrue()
    }

    @Test
    fun `Left inside typed text moves the cursor and stays in the box`() {
        query.setTextAndPlaceCursorAtEnd("news")
        show()

        searchBox.performKeyInput { pressKey(Key.DirectionLeft) }

        assertThat(leftToRail).isFalse()
        searchBox.assertIsFocused()
    }

    private fun channelRow(name: String): ChannelsViewModel.Row {
        val channel = Channel(
            id = name.hashCode().toLong(),
            sourceId = 1,
            streamId = name,
            name = name,
            streamUrl = "https://example.com/$name.ts",
            categoryId = null,
            logoUrl = "https://example.com/$name.png",
            epgChannelId = null,
            number = null,
        )
        return ChannelsViewModel.Row(LineupChannel(channel, listOf(channel)))
    }
}
