/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv

import android.content.Context
import android.view.inputmethod.InputMethodManager
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.text.input.setTextAndPlaceCursorAtEnd
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performImeAction
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.pressKey
import androidx.test.core.app.ApplicationProvider
import app.opentv.data.model.Channel
import app.opentv.data.model.Movie
import app.opentv.data.model.Series
import app.opentv.ui.ChannelsViewModel
import app.opentv.ui.channels.SearchContent
import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

// Plain Application: OpenTvApp schedules WorkManager on start, which a lone composable doesn't need.
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = TV_1080P, application = android.app.Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@OptIn(ExperimentalTestApi::class)
class SearchContentTest {

    @get:Rule
    val compose = createComposeRule()

    @get:Rule
    val artwork = PlaceholderArtwork()

    private val query = TextFieldState()

    private fun show(
        channels: List<ChannelsViewModel.Row> = emptyList(),
        movies: List<Movie> = emptyList(),
        series: List<Series> = emptyList(),
    ) {
        compose.setTvContent {
            SearchContent(
                query = query,
                channelResults = channels,
                movieResults = movies,
                seriesResults = series,
                onPlayChannel = {},
                onPlayMovie = {},
                onOpenSeries = {},
                onBack = {},
            )
        }
        compose.waitForIdle()
    }

    private val searchBox get() = compose.onNode(hasSetTextAction())

    @Test
    fun `search opens with the highlight in the search box`() {
        show()

        searchBox.assertIsFocused()
        captureScreen("search_empty")
    }

    @Test
    fun `what the device keyboard types becomes the search`() {
        show()

        searchBox.performTextInput("bbc")

        assertThat(query.text.toString()).isEqualTo("bbc")
    }

    @Test
    fun `Down from the search box moves the highlight to the first result`() {
        query.setTextAndPlaceCursorAtEnd("bbc")
        show(channels = listOf(channelRow("BBC One"), channelRow("BBC Two")))

        searchBox.performKeyInput { pressKey(Key.DirectionDown) }

        compose.onNodeWithText("BBC One").assertIsFocused()
        captureScreen("search_results")
    }

    // Choosing the box with OK on a real remote opens the keyboard the same way, but only for a
    // hardware D-pad, which Robolectric can't make; that path is checked by hand on a Fire TV.
    @Test
    fun `the keyboard opens when the box is chosen, not when the highlight arrives`() {
        show()
        assertThat(keyboardShowing()).isFalse()

        searchBox.performClick()

        assertThat(keyboardShowing()).isTrue()
    }

    @Test
    fun `the keyboard's Search key closes it and highlights the first result`() {
        query.setTextAndPlaceCursorAtEnd("bbc")
        show(channels = listOf(channelRow("BBC One"), channelRow("BBC Two")))
        searchBox.performClick()

        searchBox.performImeAction()

        assertThat(keyboardShowing()).isFalse()
        compose.onNodeWithText("BBC One").assertIsFocused()
    }

    private fun keyboardShowing(): Boolean {
        compose.waitForIdle()
        val context = ApplicationProvider.getApplicationContext<Context>()
        return shadowOf(context.getSystemService(InputMethodManager::class.java)).isSoftInputVisible
    }

    private fun channelRow(name: String) = ChannelsViewModel.Row(
        primary = Channel(
            id = name.hashCode().toLong(),
            sourceId = 1,
            streamId = name,
            name = name,
            streamUrl = "https://example.com/$name.ts",
            categoryId = null,
            logoUrl = "https://example.com/$name.png",
            epgChannelId = null,
            number = null,
        ),
        variants = emptyList(),
        now = null,
        next = null,
        programmes = emptyList(),
    )
}
