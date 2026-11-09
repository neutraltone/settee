/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv

import android.os.Looper
import android.view.KeyEvent
import android.view.View
import android.view.inputmethod.EditorInfo
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.text.input.clearText
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
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.requestFocus
import app.opentv.data.model.Channel
import app.opentv.data.model.Movie
import app.opentv.data.model.Series
import app.opentv.data.repo.LineupChannel
import app.opentv.ui.ChannelsViewModel
import app.opentv.ui.channels.SearchContent
import app.opentv.ui.options.LeaveScreen
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
    private var recentSearches by mutableStateOf<List<String>?>(emptyList())
    private var liveChannels by mutableStateOf(emptyList<ChannelsViewModel.Row>())
    private var liveMovies by mutableStateOf(emptyList<Movie>())
    private val opened = mutableListOf<String>()
    private val optionsOpenedOn = mutableListOf<OptionsSubject>()
    private var leaveThroughOptions: LeaveScreen? = null
    private var cleared = false

    private fun show(
        channels: List<ChannelsViewModel.Row> = emptyList(),
        movies: List<Movie> = emptyList(),
        series: List<Series> = emptyList(),
    ) {
        compose.setTvContent {
            view = LocalView.current
            CompositionLocalProvider(
                LocalOpenOptions provides { subject, leave ->
                    optionsOpenedOn += subject
                    leaveThroughOptions = leave
                },
            ) {
                SearchContent(
                    query = query,
                    channelResults = channels + liveChannels,
                    movieResults = movies + liveMovies,
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

    private val device = DeviceKeyboard(compose) { view }

    @Test
    fun `search opens with the highlight in the search box`() {
        show()

        assertThat(device.highlighted()).isTrue()
        captureScreen("search_empty")
    }

    @Test
    fun `what the device keyboard types becomes the search`() {
        show()

        device.type("bbc")

        assertThat(query.text.toString()).isEqualTo("bbc")
    }

    // The bug on a Fire TV: the box showed the Recent Search but the keyboard's own input stayed
    // empty, and later showed text the box no longer had.
    @Test
    fun `the keyboard reads the text a chosen Recent Search puts in the box`() {
        recentSearches = listOf("bbc")
        show()

        compose.onNodeWithText("bbc").requestFocus().performKeyInput { pressKey(Key.DirectionCenter) }

        assertThat(device.textShownBy()).isEqualTo("bbc")
    }

    @Test
    fun `the keyboard sees the box emptied when the search is cleared`() {
        show()
        device.type("bbc")

        query.clearText()

        assertThat(device.textShownBy()).isEmpty()
    }

    @Test
    fun `Down from the search box moves the highlight to the first result`() {
        query.setTextAndPlaceCursorAtEnd("bbc")
        show(channels = listOf(channelRow("BBC One"), channelRow("BBC Two")))

        device.remote(KeyEvent.KEYCODE_DPAD_DOWN)

        compose.onNodeWithText("BBC One").assertIsFocused()
        captureScreen("search_results")
    }

    @Test
    fun `the keyboard opens when the box is chosen, not when the highlight arrives`() {
        show()
        assertThat(device.showing).isFalse()

        device.remote(KeyEvent.KEYCODE_DPAD_CENTER)

        assertThat(device.showing).isTrue()
        assertThat(device.highlighted()).isTrue()
    }

    @Test
    fun `OK from a remote that sends Enter opens the keyboard too`() {
        show()

        device.remote(KeyEvent.KEYCODE_ENTER)

        assertThat(device.showing).isTrue()
    }

    @Test
    fun `OK opens the keyboard again after Back closed it`() {
        show()
        device.remote(KeyEvent.KEYCODE_DPAD_CENTER)
        device.close()
        assertThat(device.showing).isFalse()

        device.remote(KeyEvent.KEYCODE_DPAD_CENTER)

        assertThat(device.showing).isTrue()
    }

    @Test
    fun `coming back to the box after using the keyboard leaves it shut`() {
        query.setTextAndPlaceCursorAtEnd("bbc")
        show(channels = listOf(channelRow("BBC One")))
        device.remote(KeyEvent.KEYCODE_DPAD_CENTER)
        device.pressAction()
        compose.onNodeWithText("BBC One").assertIsFocused()

        device.remote(KeyEvent.KEYCODE_DPAD_UP)

        assertThat(device.highlighted()).isTrue()
        assertThat(device.showing).isFalse()
    }

    @Test
    fun `the keyboard's action key is Search`() {
        show()

        val (info, _) = device.connection()

        assertThat(info.imeOptions and EditorInfo.IME_MASK_ACTION).isEqualTo(EditorInfo.IME_ACTION_SEARCH)
    }

    @Test
    fun `the keyboard's Search key closes it and highlights the first result`() {
        query.setTextAndPlaceCursorAtEnd("bbc")
        show(channels = listOf(channelRow("BBC One"), channelRow("BBC Two")))
        device.remote(KeyEvent.KEYCODE_DPAD_CENTER)

        device.pressAction()

        assertThat(device.showing).isFalse()
        compose.onNodeWithText("BBC One").assertIsFocused()
    }

    @Test
    fun `an empty box shows the Recent Searches`() {
        recentSearches = listOf("bbc", "news")
        show()

        compose.onNodeWithText("bbc").assertIsDisplayed()
        compose.onNodeWithText("news").assertIsDisplayed()
        compose.onNodeWithText(CLEAR).assertIsDisplayed()
        assertThat(device.highlighted()).isTrue()
        captureScreen("search_recent")

        device.remote(KeyEvent.KEYCODE_DPAD_DOWN)
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
    fun `nothing shows until the Recent Searches have loaded`() {
        recentSearches = null
        show()

        compose.onNodeWithText(HINT).assertDoesNotExist()
        compose.onNodeWithText(CLEAR).assertDoesNotExist()

        recentSearches = listOf("bbc")

        compose.onNodeWithText("bbc").assertIsDisplayed()
    }

    @Test
    fun `typing hides the Recent Searches`() {
        recentSearches = listOf("bbc")
        show()

        device.type("n")

        compose.onNodeWithText("bbc").assertDoesNotExist()
    }

    @Test
    fun `choosing a Recent Search runs it and the highlight moves to its first result`() {
        recentSearches = listOf("bbc", "news")
        show()
        compose.onNodeWithText("bbc").requestFocus().performKeyInput { pressKey(Key.DirectionCenter) }

        compose.waitForIdle()
        assertThat(query.text.toString()).isEqualTo("bbc")
        assertThat(device.highlighted()).isTrue()
        assertThat(opened).isEmpty()
        assertThat(device.showing).isFalse()

        liveChannels = listOf(channelRow("BBC One"))

        waitForResultsToSettle()
        compose.onNodeWithText("BBC One").assertIsFocused()
    }

    @Test
    fun `the highlight stays in the box if the viewer opens the keyboard while results load`() {
        recentSearches = listOf("bbc")
        show()
        compose.onNodeWithText("bbc").requestFocus().performKeyInput { pressKey(Key.DirectionCenter) }
        compose.waitForIdle()
        device.remote(KeyEvent.KEYCODE_DPAD_CENTER)

        liveChannels = listOf(channelRow("BBC One"))

        waitForResultsToSettle()
        assertThat(device.highlighted()).isTrue()
    }

    @Test
    fun `the highlight stays where the viewer moved it while results load`() {
        recentSearches = listOf("bbc")
        show()
        compose.onNodeWithText("bbc").requestFocus().performKeyInput { pressKey(Key.DirectionCenter) }
        compose.waitForIdle()
        compose.onNodeWithText("Done").requestFocus()

        liveChannels = listOf(channelRow("BBC One"))

        waitForResultsToSettle()
        compose.onNodeWithText("Done").assertIsFocused()
    }

    @Test
    fun `results arriving a section at a time still highlight the first`() {
        recentSearches = listOf("bbc")
        show()
        compose.onNodeWithText("bbc").requestFocus().performKeyInput { pressKey(Key.DirectionCenter) }
        compose.waitForIdle()
        compose.mainClock.autoAdvance = false

        liveMovies = listOf(movie("Film"))
        runMainLooper()
        compose.mainClock.advanceTimeBy(50)
        liveChannels = listOf(channelRow("BBC One"))
        runMainLooper()
        compose.mainClock.advanceTimeBy(500)
        compose.mainClock.autoAdvance = true

        compose.waitForIdle()
        compose.onNodeWithText("BBC One").assertIsFocused()
    }

    @Test
    fun `a Recent Search with no results leaves the highlight in the box`() {
        recentSearches = listOf("gone")
        show()
        compose.onNodeWithText("gone").requestFocus().performKeyInput { pressKey(Key.DirectionCenter) }

        compose.waitForIdle()
        assertThat(device.highlighted()).isTrue()
    }

    @Test
    fun `typing alone saves nothing, and opening a result saves what was typed`() {
        show(channels = listOf(channelRow("BBC One")))
        device.type("bbc")
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
    fun `Menu on a movie result opens its options`() {
        query.setTextAndPlaceCursorAtEnd("film")
        val film = movie("Film")
        show(movies = listOf(film))

        compose.onNodeWithText("Film").requestFocus().performKeyInput { pressKey(Key.Menu) }

        assertThat(optionsOpenedOn).containsExactly(OptionsSubject.OfMovie(film))
        captureScreen("search_results_movie")
    }

    @Test
    fun `holding Select on a series result opens its options without opening it`() {
        query.setTextAndPlaceCursorAtEnd("show")
        val show = series("Show")
        show(series = listOf(show))

        compose.onNodeWithText("Show").requestFocus().performKeyInput {
            keyDown(Key.DirectionCenter)
            advanceEventTime(1_000)
            keyUp(Key.DirectionCenter)
        }

        assertThat(optionsOpenedOn).containsExactly(OptionsSubject.OfSeries(show))
        assertThat(opened).isEmpty()
    }

    @Test
    fun `leaving through a result's options saves the search, as opening the result does`() {
        query.setTextAndPlaceCursorAtEnd("film")
        show(movies = listOf(movie("Film")))
        compose.onNodeWithText("Film").requestFocus().performKeyInput { pressKey(Key.Menu) }
        var left = false

        leaveThroughOptions!! { left = true }

        assertThat(opened).containsExactly("film")
        assertThat(left).isTrue()
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
    fun `removing a Recent Search leaves the highlight alone once it has moved off the list`() {
        recentSearches = listOf("bbc", "news")
        show()
        compose.onNodeWithText("news").requestFocus()
        device.remote(KeyEvent.KEYCODE_DPAD_UP)
        device.remote(KeyEvent.KEYCODE_DPAD_UP)
        assertThat(device.highlighted()).isTrue()

        recentSearches = listOf("bbc")

        compose.waitForIdle()
        assertThat(device.highlighted()).isTrue()
    }

    @Test
    fun `removing the only Recent Search highlights the box`() {
        recentSearches = listOf("bbc")
        show()
        compose.onNodeWithText("bbc").requestFocus()

        recentSearches = emptyList()

        compose.waitForIdle()
        assertThat(device.highlighted()).isTrue()
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
        assertThat(device.highlighted()).isTrue()
    }

    // With the clock stopped, the box's EditText holds frames back until Android's own main
    // looper has run, so a change wouldn't be composed by advancing the clock alone.
    private fun runMainLooper() = shadowOf(Looper.getMainLooper()).idle()

    // waitForIdle doesn't wait out a delay with nothing else pending.
    private fun waitForResultsToSettle() {
        compose.waitForIdle()
        compose.mainClock.advanceTimeBy(500)
        compose.waitForIdle()
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
