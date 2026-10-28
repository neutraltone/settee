/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertIsNotFocused
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasScrollToNodeAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import app.opentv.data.model.Channel
import app.opentv.data.model.Movie
import app.opentv.data.model.Series
import app.opentv.ui.HomeContent
import app.opentv.ui.VodViewModel
import com.google.common.truth.Truth.assertThat
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
class HomeContentTest {

    @get:Rule
    val compose = createComposeRule()

    @get:Rule
    val artwork = PlaceholderArtwork()

    private val resume = listOf(
        VodViewModel.ResumeItem(
            "movie:9",
            "Collateral",
            playerTitle = "Collateral",
            posterUrl = "https://example.com/9.jpg",
            streamUrl = "",
            progress = 0.4f,
        ),
    )

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

    private fun series(id: Long, name: String) = Series(
        id = id,
        sourceId = 1,
        seriesId = "$id",
        name = name,
        categoryId = null,
        posterUrl = "https://example.com/$id.jpg",
        rating = null,
        year = null,
        plot = null,
    )

    private fun channel(id: Long, name: String) = Channel(
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

    /** The channel a test's Home last played. */
    private var played: Channel? = null

    private fun setHome(
        resume: List<VodViewModel.ResumeItem> = this.resume,
        shows: List<Series> = listOf(series(1, "Severance")),
        movies: List<Movie> = listOf(movie(2, "Heat")),
        channels: List<Channel> = listOf(channel(3, "BBC One")),
        takeFocus: Boolean = true,
    ) = compose.setTvContent {
        HomeContent(
            continueWatching = resume,
            favouriteShows = shows,
            favouriteMovies = movies,
            favouriteChannels = channels,
            onResume = { _, _, _ -> },
            onOpenSeries = {},
            onOpenMovie = {},
            onPlayChannel = { played = it },
            takeFocus = takeFocus,
        )
    }

    private fun topOf(text: String) = compose.onNodeWithText(text).getUnclippedBoundsInRoot().top

    @Test
    fun `rows run Continue Watching, Favourite Shows, Favourite Movies, Favourite Channels`() {
        setHome()

        assertThat(topOf("Continue watching")).isLessThan(topOf("Favourite shows"))
        assertThat(topOf("Favourite shows")).isLessThan(topOf("Favourite movies"))
        // Four rows overflow a TV screen; scrolling down brings the last one in under Movies.
        compose.onAllNodes(hasScrollToNodeAction())[0].performScrollToNode(hasText("Favourite channels"))
        assertThat(topOf("Favourite movies")).isLessThan(topOf("Favourite channels"))
        compose.onNodeWithText("BBC One").assertExists()
    }

    @Test
    fun `a row with nothing in it is left out`() {
        setHome(shows = emptyList(), movies = emptyList())

        compose.onNodeWithText("Favourite shows").assertDoesNotExist()
        compose.onNodeWithText("Favourite movies").assertDoesNotExist()
        assertThat(topOf("Continue watching")).isLessThan(topOf("Favourite channels"))
        captureScreen("home_empty_rows_hidden")
    }

    @Test
    fun `a Profile with nothing watched or favourited gets a hint instead of rows`() {
        setHome(resume = emptyList(), shows = emptyList(), movies = emptyList(), channels = emptyList())

        compose.onNodeWithText("Things you watch and favourite will show up here").assertExists()
        captureScreen("home_hint")
    }

    @Test
    fun `Home opens with the first card of the top row highlighted`() {
        setHome()

        compose.onNodeWithText("Collateral").assertIsFocused()
        captureScreen("home_initial")
    }

    @Test
    fun `with nothing to continue, the first Favourite show is highlighted`() {
        setHome(resume = emptyList())

        compose.onNodeWithText("Severance").assertIsFocused()
        captureScreen("home_no_continue_watching")
    }

    @Test
    fun `selecting a Favourite channel plays it`() {
        setHome(resume = emptyList(), shows = emptyList(), movies = emptyList())

        compose.onNodeWithText("BBC One").performClick()

        assertThat(played).isEqualTo(channel(3, "BBC One"))
    }

    @Test
    fun `Home picked from the side menu leaves the highlight on the menu`() {
        setHome(takeFocus = false)

        compose.onNodeWithText("Collateral").assertIsNotFocused()
    }

    @Test
    fun `when the highlighted card's row goes, the highlight moves to the row that takes its place`() {
        var resume by mutableStateOf(this.resume)
        compose.setTvContent {
            HomeContent(
                continueWatching = resume,
                favouriteShows = listOf(series(1, "Severance")),
                favouriteMovies = emptyList(),
                favouriteChannels = emptyList(),
                onResume = { _, _, _ -> },
                onOpenSeries = {},
                onOpenMovie = {},
                onPlayChannel = {},
                takeFocus = true,
            )
        }
        compose.onNodeWithText("Collateral").assertIsFocused()

        resume = emptyList()

        compose.onNodeWithText("Severance").assertIsFocused()
    }
}
