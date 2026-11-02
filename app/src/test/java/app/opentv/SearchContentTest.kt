/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv

import android.content.Context
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import android.view.inputmethod.InputMethodManager
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.text.input.setTextAndPlaceCursorAtEnd
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performImeAction
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.requestFocus
import androidx.test.core.app.ApplicationProvider
import app.opentv.data.model.Channel
import app.opentv.data.model.Movie
import app.opentv.data.model.Series
import app.opentv.data.repo.LineupChannel
import app.opentv.ui.ChannelsViewModel
import app.opentv.ui.channels.SearchContent
import app.opentv.ui.options.LocalOpenOptions
import app.opentv.ui.options.OptionsSubject
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
    private lateinit var view: View
    private var recentSearches by mutableStateOf(emptyList<String>())
    private var liveChannels by mutableStateOf(emptyList<ChannelsViewModel.Row>())
    private val opened = mutableListOf<String>()
    private val optionsOpenedOn = mutableListOf<OptionsSubject>()
    private var cleared = false

    private fun show(
        channels: List<ChannelsViewModel.Row> = emptyList(),
        movies: List<Movie> = emptyList(),
        series: List<Series> = emptyList(),
    ) {
        compose.setTvContent {
            view = LocalView.current
            CompositionLocalProvider(LocalOpenOptions provides { optionsOpenedOn += it }) {
                SearchContent(
                    query = query,
                    channelResults = channels + liveChannels,
                    movieResults = movies,
                    seriesResults = series,
                    onPlayChannel = {},
                    onPlayMovie = {},
                    onOpenSeries = {},
                    onBack = {},
                    recentSearches = recentSearches,
                    onResultOpened = { opened += it },
                    onClearRecentSearches = { cleared = true },
                )
            }
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

    @Test
    fun `the keyboard opens when the box is chosen, not when the highlight arrives`() {
        show()
        assertThat(keyboardShowing()).isFalse()

        searchBox.performClick()

        assertThat(keyboardShowing()).isTrue()
    }

    // The shadow's "showing" flag goes up for any show() call, even with nothing to type into, and
    // that is what a Fire TV ignored: a real keyboard also needs the input connection.
    @Test
    fun `OK on the highlighted box opens a keyboard that can type into it`() {
        show()
        assertThat(inputConnection()).isNull()

        searchBox.performKeyInput { pressKey(Key.DirectionCenter) }

        assertThat(keyboardShowing()).isTrue()
        assertThat(inputConnection()).isNotNull()
    }

    @Test
    fun `OK opens the keyboard again after Back closed it`() {
        show()
        searchBox.performKeyInput { pressKey(Key.DirectionCenter) }
        compose.waitForIdle()
        keyboard().hideSoftInputFromWindow(view.windowToken, 0)
        assertThat(keyboardShowing()).isFalse()

        searchBox.performKeyInput { pressKey(Key.DirectionCenter) }

        assertThat(keyboardShowing()).isTrue()
    }

    @Test
    fun `coming back to the box after using the keyboard leaves it shut`() {
        query.setTextAndPlaceCursorAtEnd("bbc")
        show(channels = listOf(channelRow("BBC One")))
        searchBox.performKeyInput { pressKey(Key.DirectionCenter) }
        searchBox.performImeAction()
        compose.onNodeWithText("BBC One").assertIsFocused()

        compose.onNodeWithText("BBC One").performKeyInput { pressKey(Key.DirectionUp) }

        searchBox.assertIsFocused()
        assertThat(keyboardShowing()).isFalse()
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

    @Test
    fun `an empty box shows the Recent Searches`() {
        recentSearches = listOf("bbc", "news")
        show()

        compose.onNodeWithText("bbc").assertIsDisplayed()
        compose.onNodeWithText("news").assertIsDisplayed()
        compose.onNodeWithText(CLEAR).assertIsDisplayed()
        searchBox.assertIsFocused()
        captureScreen("search_recent")

        searchBox.performKeyInput { pressKey(Key.DirectionDown) }
        compose.onNodeWithText("bbc").assertIsFocused()
        captureScreen("search_recent_highlighted")
    }

    @Test
    fun `with no Recent Searches the hint shows and there is nothing to clear`() {
        show()

        compose.onNodeWithText(CLEAR).assertDoesNotExist()
        compose.onNodeWithText(HINT).assertIsDisplayed()
    }

    @Test
    fun `typing hides the Recent Searches`() {
        recentSearches = listOf("bbc")
        show()

        searchBox.performTextInput("n")

        compose.onNodeWithText("bbc").assertDoesNotExist()
    }

    @Test
    fun `choosing a Recent Search runs it and the highlight moves to its first result`() {
        recentSearches = listOf("bbc", "news")
        show()
        compose.onNodeWithText("bbc").requestFocus().performKeyInput { pressKey(Key.DirectionCenter) }

        compose.waitForIdle()
        assertThat(query.text.toString()).isEqualTo("bbc")
        searchBox.assertIsFocused()
        assertThat(opened).isEmpty()
        assertThat(keyboardShowing()).isFalse()

        liveChannels = listOf(channelRow("BBC One"))

        compose.waitForIdle()
        compose.onNodeWithText("BBC One").assertIsFocused()
    }

    @Test
    fun `a Recent Search with no results leaves the highlight in the box`() {
        recentSearches = listOf("gone")
        show()
        compose.onNodeWithText("gone").requestFocus().performKeyInput { pressKey(Key.DirectionCenter) }

        compose.waitForIdle()
        searchBox.assertIsFocused()
    }

    @Test
    fun `typing alone saves nothing, and opening a result saves what was typed`() {
        show(channels = listOf(channelRow("BBC One")))
        searchBox.performTextInput("bbc")
        compose.waitForIdle()
        assertThat(opened).isEmpty()

        compose.onNodeWithText("BBC One").performClick()

        assertThat(opened).containsExactly("bbc")
    }

    @Test
    fun `opening a movie or a series result saves the search too`() {
        query.setTextAndPlaceCursorAtEnd("bbc")
        show(
            movies = listOf(movie("Film")),
            series = listOf(series("Show")),
        )

        compose.onNodeWithText("Film").performClick()
        compose.onNodeWithText("Show").performClick()

        assertThat(opened).containsExactly("bbc", "bbc")
    }

    @Test
    fun `Menu on a Recent Search opens its options`() {
        recentSearches = listOf("bbc")
        show()

        compose.onNodeWithText("bbc").requestFocus().performKeyInput { pressKey(Key.Menu) }

        assertThat(optionsOpenedOn).containsExactly(OptionsSubject.OfRecentSearch("bbc"))
    }

    @Test
    fun `removing a Recent Search highlights the one that took its place`() {
        recentSearches = listOf("bbc", "news", "film")
        show()
        compose.onNodeWithText("news").requestFocus()

        recentSearches = listOf("bbc", "film")

        compose.waitForIdle()
        compose.onNodeWithText("film").assertIsFocused()
    }

    @Test
    fun `removing the last in the list highlights the one above`() {
        recentSearches = listOf("bbc", "news")
        show()
        compose.onNodeWithText("news").requestFocus()

        recentSearches = listOf("bbc")

        compose.waitForIdle()
        compose.onNodeWithText("bbc").assertIsFocused()
    }

    @Test
    fun `removing the only Recent Search highlights the box`() {
        recentSearches = listOf("bbc")
        show()
        compose.onNodeWithText("bbc").requestFocus()

        recentSearches = emptyList()

        compose.waitForIdle()
        searchBox.assertIsFocused()
        compose.onNodeWithText(HINT).assertIsDisplayed()
    }

    @Test
    fun `clearing the Recent Searches highlights the box`() {
        recentSearches = listOf("bbc", "news")
        show()

        compose.onNodeWithText(CLEAR).requestFocus().performKeyInput { pressKey(Key.DirectionCenter) }
        assertThat(cleared).isTrue()
        recentSearches = emptyList()

        compose.waitForIdle()
        searchBox.assertIsFocused()
    }

    private fun keyboardShowing(): Boolean {
        compose.waitForIdle()
        return shadowOf(keyboard()).isSoftInputVisible
    }

    private fun keyboard() =
        ApplicationProvider.getApplicationContext<Context>().getSystemService(InputMethodManager::class.java)

    private fun inputConnection(): InputConnection? {
        compose.waitForIdle()
        return compose.runOnIdle { view.onCreateInputConnection(EditorInfo()) }
    }

    private fun movie(name: String) = Movie(
        sourceId = 1,
        streamId = name,
        name = name,
        categoryId = null,
        posterUrl = null,
        rating = null,
        year = null,
        plot = null,
        durationSeconds = null,
        containerExtension = null,
        streamUrl = "https://example.com/$name.mp4",
    )

    private fun series(name: String) = Series(
        sourceId = 1,
        seriesId = name,
        name = name,
        categoryId = null,
        posterUrl = null,
        rating = null,
        year = null,
        plot = null,
    )

    private fun channelRow(name: String) = Channel(
        id = name.hashCode().toLong(),
        sourceId = 1,
        streamId = name,
        name = name,
        streamUrl = "https://example.com/$name.ts",
        categoryId = null,
        logoUrl = "https://example.com/$name.png",
        epgChannelId = null,
        number = null,
    ).let { ChannelsViewModel.Row(LineupChannel(it, listOf(it))) }
}

private const val CLEAR = "Clear recent searches"
private const val HINT = "Start typing to search channels, movies and shows."
