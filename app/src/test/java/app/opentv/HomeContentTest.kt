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
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasScrollToNodeAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.pressKey
import app.opentv.data.model.Channel
import app.opentv.data.model.Movie
import app.opentv.data.model.Series
import app.opentv.ui.HomeContent
import app.opentv.ui.RecentRows
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
@OptIn(ExperimentalTestApi::class)
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

    /** False when a test's Home was picked in the side menu, where the highlight stays. */
    private var takeFocus = true

    /** The movie a test's Home last opened. */
    private var opened: Movie? = null

    private val recent = RecentRows(
        releasedMovies = listOf(movie(10, "Dune: Part Two")),
        addedMovies = listOf(movie(11, "Drive")),
        releasedShows = listOf(series(12, "Slow Horses")),
        addedShows = listOf(series(13, "Shogun")),
    )

    private fun scrollTo(text: String) {
        compose.onAllNodes(hasScrollToNodeAction())[0].performScrollToNode(hasText(text))
    }

    private fun setHome(
        resume: List<VodViewModel.ResumeItem> = this.resume,
        shows: List<Series> = listOf(series(1, "Severance")),
        movies: List<Movie> = listOf(movie(2, "Heat")),
        channels: List<Channel> = listOf(channel(3, "BBC One")),
        recent: RecentRows = RecentRows(),
    ) = compose.setTvContent {
        HomeContent(
            continueWatching = resume,
            favouriteShows = shows,
            favouriteMovies = movies,
            favouriteChannels = channels,
            recent = recent,
            onResume = { _, _, _ -> },
            onOpenSeries = {},
            onOpenMovie = { opened = it },
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
    fun `after Favourite Channels come recently released and added movies, then shows`() {
        setHome(recent = recent)

        val order = listOf(
            "Favourite channels",
            "Recently released movies",
            "Recently added movies",
            "Recently released shows",
            "Recently added shows",
        )
        // Nine rows are far taller than a TV screen: bring each in under the one before it.
        order.zipWithNext { above, below ->
            scrollTo(below)
            assertThat(topOf(above)).isLessThan(topOf(below))
        }
        compose.onNodeWithText("Shogun").assertExists()
    }

    @Test
    fun `the remote's down button reaches the last of the recent rows`() {
        setHome(
            resume = emptyList(),
            shows = emptyList(),
            movies = emptyList(),
            channels = emptyList(),
            recent = recent,
        )
        compose.onNodeWithText("Dune: Part Two").assertIsFocused()
        captureScreen("home_recent_rows")

        repeat(3) { compose.onRoot().performKeyInput { pressKey(Key.DirectionDown) } }

        compose.onNodeWithText("Shogun").assertIsFocused()
    }

    @Test
    fun `selecting a recently released movie opens it`() {
        setHome(recent = recent)
        scrollTo("Dune: Part Two")

        compose.onNodeWithText("Dune: Part Two").performClick()

        assertThat(opened).isEqualTo(movie(10, "Dune: Part Two"))
    }

    @Test
    fun `with only recent titles to show, Home shows them rather than the hint`() {
        setHome(
            resume = emptyList(),
            shows = emptyList(),
            movies = emptyList(),
            channels = emptyList(),
            recent = RecentRows(addedMovies = listOf(movie(11, "Drive"))),
        )

        compose.onNodeWithText("Things you watch and favourite will show up here").assertDoesNotExist()
        compose.onNodeWithText("Recently released movies").assertDoesNotExist()
        compose.onNodeWithText("Drive").assertIsFocused()
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
    fun `a show with a New Episode is labelled in Home's Continue Watching`() {
        val newEpisode = VodViewModel.ResumeItem(
            "ep:31",
            "Severance",
            playerTitle = "S3E1 · Cold Harbor",
            posterUrl = "https://example.com/31.jpg",
            streamUrl = "",
            progress = null,
            episode = VodViewModel.SeasonEpisode(season = 3, episode = 1),
            newEpisode = true,
        )
        setHome(resume = listOf(newEpisode) + resume)

        compose.onNodeWithText("New episode", useUnmergedTree = true).assertExists()
        captureScreen("home_continue_watching_new_episode")
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
        takeFocus = false

        setHome()

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
