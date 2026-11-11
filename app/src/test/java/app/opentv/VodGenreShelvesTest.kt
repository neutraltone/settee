/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv

import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.requestFocus
import app.opentv.data.model.Movie
import app.opentv.data.model.Series
import app.opentv.data.repo.GenreGroup
import app.opentv.ui.design.ScreenFrame
import app.opentv.ui.vod.MovieShelves
import app.opentv.ui.vod.SeriesShelves
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** For you's genre rows under Recently added, on Movies and Shows. */
// Plain Application: OpenTvApp schedules WorkManager on start, which lone composables don't need.
@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = TV_1080P, application = android.app.Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class VodGenreShelvesTest {

    @get:Rule
    val compose = createComposeRule()

    @get:Rule
    val artwork = PlaceholderArtwork()

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

    private fun movies(from: Long, vararg names: String) = names.mapIndexed { i, name -> movie(from + i, name) }

    private fun series(from: Long, vararg names: String) = names.mapIndexed { i, name -> series(from + i, name) }

    private fun moveDownFrom(title: String) {
        compose.onNodeWithText(title).performKeyInput { pressKey(Key.DirectionDown) }
        compose.mainClock.advanceTimeBy(1_000)
        compose.waitForIdle()
    }

    @Test
    fun `Down from Recently added goes through Movies' genre rows in the order they come`() {
        compose.setTvContent {
            ScreenFrame {
                MovieShelves(
                    resume = emptyList(),
                    favourites = emptyList(),
                    recommended = emptyList(),
                    recentlyAdded = movies(1, "Alien", "Brazil"),
                    genreRows = listOf(
                        GenreGroup("Comedy", movies(10, "Airplane", "Clue", "Big", "Tootsie")),
                        GenreGroup("Western", movies(20, "Unforgiven", "Tombstone", "Shane")),
                        GenreGroup("Thriller", movies(30, "Ronin", "Drive", "Collateral")),
                    ),
                    onResume = {},
                    onOpenMovie = {},
                )
            }
        }
        compose.onNodeWithText("Alien").requestFocus()

        moveDownFrom("Alien")
        compose.onNodeWithText("Airplane").assertIsFocused()
        captureScreen("movies_genre_rows")

        moveDownFrom("Airplane")
        compose.onNodeWithText("Unforgiven").assertIsFocused()
    }

    @Test
    fun `Down from Recently added goes through Shows' genre rows in the order they come`() {
        compose.setTvContent {
            ScreenFrame {
                SeriesShelves(
                    resume = emptyList(),
                    favourites = emptyList(),
                    recommended = emptyList(),
                    recentlyAdded = series(1, "Slow Horses", "Severance"),
                    genreRows = listOf(
                        GenreGroup("Crime", series(10, "Ozark", "The Wire", "Fargo")),
                        GenreGroup("Comedy", series(20, "Taskmaster", "Ghosts", "Detectorists")),
                        GenreGroup("Drama", series(30, "Succession", "The Crown")),
                    ),
                    onResume = {},
                    onOpenSeries = {},
                )
            }
        }
        compose.onNodeWithText("Slow Horses").requestFocus()

        moveDownFrom("Slow Horses")
        compose.onNodeWithText("Ozark").assertIsFocused()
        captureScreen("shows_genre_rows")

        moveDownFrom("Ozark")
        compose.onNodeWithText("Taskmaster").assertIsFocused()
    }
}
