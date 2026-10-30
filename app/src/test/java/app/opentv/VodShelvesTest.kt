/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv

import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import app.opentv.data.model.Movie
import app.opentv.data.model.Series
import app.opentv.data.repo.MediaKey
import app.opentv.ui.VodViewModel
import app.opentv.ui.vod.MovieShelves
import app.opentv.ui.vod.SeriesShelves
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
class VodShelvesTest {

    @get:Rule
    val compose = createComposeRule()

    @get:Rule
    val artwork = PlaceholderArtwork()

    private val resume = listOf(
        VodViewModel.ResumeItem(
            MediaKey.Movie(9),
            "Collateral",
            posterUrl = null,
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

    private fun topOf(text: String) = compose.onNodeWithText(text).getUnclippedBoundsInRoot().top

    @Test
    fun `Movies shows Favourite movies in a row under Continue Watching`() {
        compose.setTvContent {
            MovieShelves(
                resume = resume,
                favourites = listOf(movie(1, "Heat")),
                recommended = emptyList(),
                recentlyAdded = listOf(movie(2, "Ronin")),
                genreRows = emptyList(),
                onResume = {},
                onOpenMovie = {},
            )
        }

        compose.onNodeWithText("Heat").assertExists()
        assertThat(topOf("Favourites")).isGreaterThan(topOf("Continue watching"))
        assertThat(topOf("Favourites")).isLessThan(topOf("Recently added"))
        captureScreen("movies_favourites")
    }

    @Test
    fun `Movies has no Favourites row when the Profile has none`() {
        compose.setTvContent {
            MovieShelves(
                resume = resume,
                favourites = emptyList(),
                recommended = emptyList(),
                recentlyAdded = listOf(movie(2, "Ronin")),
                genreRows = emptyList(),
                onResume = {},
                onOpenMovie = {},
            )
        }

        compose.onNodeWithText("Favourites").assertDoesNotExist()
    }

    @Test
    fun `Shows shows Favourite series in a row under Continue Watching`() {
        compose.setTvContent {
            SeriesShelves(
                resume = resume,
                favourites = listOf(series(1, "Severance")),
                recentlyAdded = listOf(series(2, "Slow Horses")),
                genreRows = emptyList(),
                onResume = {},
                onOpenSeries = {},
            )
        }

        compose.onNodeWithText("Severance").assertExists()
        assertThat(topOf("Favourites")).isGreaterThan(topOf("Continue watching"))
        assertThat(topOf("Favourites")).isLessThan(topOf("Recently added"))
        captureScreen("shows_favourites")
    }

    @Test
    fun `Shows has no Favourites row when the Profile has none`() {
        compose.setTvContent {
            SeriesShelves(
                resume = resume,
                favourites = emptyList(),
                recentlyAdded = listOf(series(2, "Slow Horses")),
                genreRows = emptyList(),
                onResume = {},
                onOpenSeries = {},
            )
        }

        compose.onNodeWithText("Favourites").assertDoesNotExist()
    }
}
