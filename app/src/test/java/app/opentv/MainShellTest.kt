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
import androidx.compose.ui.test.assertIsNotFocused
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import app.opentv.data.model.Movie
import app.opentv.data.model.Series
import app.opentv.ui.HomeContent
import app.opentv.ui.MainShell
import app.opentv.ui.RecentRows
import app.opentv.ui.Tab
import app.opentv.ui.visibleTabs
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

// Plain Application: OpenTvApp schedules WorkManager on start, which lone composables don't need.
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = TV_1080P, application = android.app.Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@OptIn(ExperimentalTestApi::class)
class MainShellTest {

    @get:Rule
    val compose = createComposeRule()

    @get:Rule
    val artwork = PlaceholderArtwork()

    /** The side menu with Home open, showing [recent] and nothing watched or favourited. */
    private fun setShell(recent: () -> RecentRows = { RecentRows() }) = compose.setTvContent {
        MainShell(
            tabs = visibleTabs(liveEnabled = true, moviesEnabled = true, seriesEnabled = true),
            current = Tab.HOME,
            onSelect = {},
            onOpenSearch = {},
            onOpenSettings = {},
            onOpenProfiles = {},
            activeProfileName = "Sam",
        ) { _, takeFocus, onNothingToShow ->
            HomeContent(
                continueWatching = emptyList(),
                favouriteShows = emptyList<Series>(),
                favouriteMovies = emptyList<Movie>(),
                favouriteChannels = emptyList(),
                recent = recent(),
                onResume = { _, _, _ -> },
                onOpenSeries = {},
                onOpenMovie = {},
                onPlayChannel = {},
                takeFocus = takeFocus,
                onNothingToShow = onNothingToShow,
            )
        }
    }

    @Test
    fun `with nothing on Home, the side menu's Home item is highlighted`() {
        setShell()

        compose.onNodeWithContentDescription("Home").assertIsFocused()
        captureScreen("home_nothing_to_show")
    }

    @Test
    fun `from there, the remote's down button reaches every item in the side menu`() {
        setShell()
        compose.onNodeWithContentDescription("Home").assertIsFocused()

        for (item in listOf("Live TV", "Movies", "Shows", "Recordings", "Search", "Sam", "Settings")) {
            compose.onRoot().performKeyInput { pressKey(Key.DirectionDown) }
            compose.onNodeWithContentDescription(item).assertIsFocused()
        }
    }

    @Test
    fun `when titles arrive while the side menu is highlighted, the highlight stays there`() {
        var recent by mutableStateOf(RecentRows())
        setShell { recent }
        compose.onNodeWithContentDescription("Home").assertIsFocused()

        recent = RecentRows(addedMovies = listOf(movie(11, "Drive")))

        compose.onNodeWithText("Drive").assertIsNotFocused()
        compose.onNodeWithContentDescription("Home").assertIsFocused()
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
}
