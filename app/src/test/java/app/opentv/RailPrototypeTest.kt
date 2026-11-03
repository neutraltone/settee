/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
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
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.ParameterizedRobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

// PROTOTYPE for #90: checks that A and B keep the focus rules, and renders them for comparison.
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
    private val name = variant.name.lowercase()

    @After
    fun resetVariant() {
        RailPrototype.variant = RailVariant.A
    }

    private fun setShell() {
        RailPrototype.variant = variant
        compose.setTvContent {
            MainShell(
                tabs = visibleTabs(liveEnabled = true, moviesEnabled = true, seriesEnabled = true),
                current = Tab.SHOWS,
                onSelect = {},
                onOpenSearch = {},
                onOpenSettings = {},
                onOpenProfiles = {},
                activeProfileName = "Sam",
                modifier = Modifier.fillMaxSize(),
            ) { _, takeFocus, onNothingToShow ->
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
        fun variants() = listOf(arrayOf(RailVariant.A), arrayOf(RailVariant.B))
    }
}
