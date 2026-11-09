/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.Text
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertIsNotFocused
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import app.opentv.data.model.Movie
import app.opentv.data.model.Series
import app.opentv.ui.HomeContent
import app.opentv.ui.MainShell
import app.opentv.ui.RailPrototype
import app.opentv.ui.RailVariant
import app.opentv.ui.RecentRows
import app.opentv.ui.Tab
import app.opentv.ui.visibleTabs
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.delay
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.ParameterizedRobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

// PROTOTYPE for #90: checks that A, B, C and D keep the focus rules, and renders them for comparison.
@RunWith(ParameterizedRobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = TV_1080P, application = android.app.Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@OptIn(ExperimentalTestApi::class)
class RailPrototypeTest(private val variant: RailVariant) {

    @get:Rule
    val compose = createComposeRule()

    @get:Rule
    val artwork = PlaceholderArtwork()

    private var focusedCard = ""
    private var selected: Tab? = null
    private val name = variant.name.lowercase()

    @After
    fun resetVariant() {
        RailPrototype.variant = RailVariant.D
    }

    private fun setShell() {
        RailPrototype.variant = variant
        var current by mutableStateOf(Tab.SHOWS)
        compose.setTvContent {
            MainShell(
                tabs = visibleTabs(liveEnabled = true, moviesEnabled = true, seriesEnabled = true),
                current = current,
                onSelect = {
                    selected = it
                    current = it
                },
                onOpenSearch = {},
                onOpenSettings = {},
                onOpenProfiles = {},
                activeProfileName = "Sam",
                modifier = Modifier.fillMaxSize(),
            ) { tab, takeFocus, onNothingToShow ->
                key(tab) {
                HomeContent(
                    continueWatching = emptyList(),
                    favouriteShows = emptyList<Series>(),
                    favouriteMovies = emptyList<Movie>(),
                    favouriteChannels = emptyList(),
                    recent = RecentRows(
                        addedMovies = (1L..8L).map { movie(it, "Film $it") },
                        releasedMovies = (11L..18L).map { movie(it, "Film $it") },
                    ),
                    onResume = {},
                    onOpenSeries = {},
                    onOpenMovie = { focusedCard = it.name },
                    onPlayChannel = {},
                    takeFocus = takeFocus,
                    onNothingToShow = onNothingToShow,
                )
                }
            }
        }
    }

    private fun press(key: Key, times: Int = 1) = repeat(times) {
        compose.onRoot().performKeyInput { pressKey(key) }
        compose.waitForIdle()
    }

    @Test
    fun `Left enters on the current tab wherever the highlight was, and Right returns to the same card`() {
        setShell()
        captureScreen("rail_${name}_content")
        press(Key.DirectionDown)
        // A card can only reach the rail from the first card of its row, so that's the card left.
        press(Key.DirectionCenter)
        val left = focusedCard
        assertThat(left).isEqualTo("Film 1")

        press(Key.DirectionLeft)
        compose.onNodeWithContentDescription("Shows").assertIsFocused()
        captureScreen("rail_${name}_rail_open")

        press(Key.DirectionRight)
        captureScreen("rail_${name}_back_on_card")
        press(Key.DirectionCenter)
        assertThat(focusedCard).isEqualTo(left)
    }

    @Test
    fun `OK on another tab opens it and moves the highlight onto its first card`() {
        setShell()
        press(Key.DirectionLeft)
        press(Key.DirectionDown)
        compose.onNodeWithContentDescription("Recordings").assertIsFocused()

        press(Key.DirectionCenter)
        compose.mainClock.advanceTimeBy(300)
        compose.waitForIdle()
        assertThat(selected).isEqualTo(Tab.RECORDINGS)
        compose.onNodeWithContentDescription("Recordings").assertIsNotFocused()
        captureScreen("rail_${name}_after_pick")

        press(Key.DirectionCenter)
        assertThat(focusedCard).isEqualTo("Film 11")
    }

    @Test
    fun `a tab that takes the highlight itself once loaded isn't beaten to it`() {
        RailPrototype.variant = variant
        var current by mutableStateOf(Tab.SHOWS)
        compose.setTvContent {
            MainShell(
                tabs = visibleTabs(liveEnabled = true, moviesEnabled = true, seriesEnabled = true),
                current = current,
                onSelect = { current = it },
                onOpenSearch = {},
                onOpenSettings = {},
                onOpenProfiles = {},
                activeProfileName = "Sam",
                modifier = Modifier.fillMaxSize(),
            ) { tab, takeFocus, _ ->
                key(tab) {
                    val channel = remember { FocusRequester() }
                    val takeIt by rememberUpdatedState(takeFocus)
                    // Like the guide: it decides whether to take the highlight when its rows arrive.
                    LaunchedEffect(Unit) {
                        delay(500)
                        if (tab == Tab.LIVE && takeIt) channel.requestFocus()
                    }
                    Column {
                        Text("Watch", Modifier.testTag("watch").clickable {})
                        Text("Channel", Modifier.testTag("channel").focusRequester(channel).clickable {})
                    }
                }
            }
        }
        press(Key.DirectionRight)
        press(Key.DirectionLeft)
        press(Key.DirectionUp, times = 2)
        compose.onNodeWithContentDescription("Live TV").assertIsFocused()

        press(Key.DirectionCenter)
        compose.mainClock.advanceTimeBy(1000)
        compose.waitForIdle()

        compose.onNodeWithTag("channel").assertIsFocused()
    }

    @Test
    fun `OK on the tab already open goes back to the row she left`() {
        setShell()
        press(Key.DirectionDown)
        press(Key.DirectionLeft)

        press(Key.DirectionCenter)
        compose.mainClock.advanceTimeBy(300)
        compose.waitForIdle()
        compose.onNodeWithContentDescription("Shows").assertIsNotFocused()
        captureScreen("rail_${name}_same_tab_pick")

        press(Key.DirectionCenter)
        assertThat(focusedCard).isEqualTo("Film 1")
    }

    @Test
    fun `the rail doesn't let the highlight fall out of its ends`() {
        setShell()
        press(Key.DirectionLeft)
        compose.onNodeWithContentDescription("Shows").assertIsFocused()

        press(Key.DirectionDown, times = 6)
        compose.onNodeWithContentDescription("Settings").assertIsFocused()
        captureScreen("rail_${name}_bottom_item")
    }

    private fun movie(id: Long, name: String) = Movie(
        id = id,
        sourceId = 1,
        streamId = "$id",
        name = name,
        categoryId = null,
        posterUrl = "https://example.com/$id.jpg",
        rating = null,
        year = null,
        plot = null,
        durationSeconds = null,
        containerExtension = "mp4",
        streamUrl = "",
    )

    companion object {
        @JvmStatic
        @ParameterizedRobolectricTestRunner.Parameters(name = "{0}")
        fun variants() = listOf(RailVariant.A, RailVariant.B, RailVariant.C, RailVariant.D).map { arrayOf(it) }
    }
}
