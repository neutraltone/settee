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
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.requestFocus
import app.opentv.data.model.Movie
import app.opentv.data.model.Series
import app.opentv.data.repo.MediaKey
import app.opentv.ui.ResumeItem
import app.opentv.ui.design.ScreenFrame
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
@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = TV_1080P, application = android.app.Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class VodShelvesTest {

    @get:Rule
    val compose = createComposeRule()

    @get:Rule
    val artwork = PlaceholderArtwork()

    private val resume = listOf(
        ResumeItem(
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
            ScreenFrame {
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
        }

        compose.onNodeWithText("Heat").assertExists()
        assertThat(topOf("Favourites")).isGreaterThan(topOf("Continue watching"))
        assertThat(topOf("Favourites")).isLessThan(topOf("Recently added"))
        captureScreen("movies_favourites")
    }

    @Test
    fun `Movies has no Favourites row when the Profile has none`() {
        compose.setTvContent {
            ScreenFrame {
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
        }

        compose.onNodeWithText("Favourites").assertDoesNotExist()
    }

    @Test
    fun `Shows shows Favourite series in a row under Continue Watching`() {
        compose.setTvContent {
            ScreenFrame {
                SeriesShelves(
                    resume = resume,
                    favourites = listOf(series(1, "Severance")),
                    recommended = emptyList(),
                    recentlyAdded = listOf(series(2, "Slow Horses")),
                    genreRows = emptyList(),
                    onResume = {},
                    onOpenSeries = {},
                )
            }
        }

        compose.onNodeWithText("Severance").assertExists()
        assertThat(topOf("Favourites")).isGreaterThan(topOf("Continue watching"))
        assertThat(topOf("Favourites")).isLessThan(topOf("Recently added"))
        captureScreen("shows_favourites")
    }

    @Test
    fun `Shows has no Favourites row when the Profile has none`() {
        compose.setTvContent {
            ScreenFrame {
                SeriesShelves(
                    resume = resume,
                    favourites = emptyList(),
                    recommended = emptyList(),
                    recentlyAdded = listOf(series(2, "Slow Horses")),
                    genreRows = emptyList(),
                    onResume = {},
                    onOpenSeries = {},
                )
            }
        }

        compose.onNodeWithText("Favourites").assertDoesNotExist()
    }

    @Test
    fun `Shows recommends series below Continue Watching and Favourites`() {
        compose.setTvContent {
            ScreenFrame {
                SeriesShelves(
                    resume = resume,
                    favourites = listOf(series(1, "Severance")),
                    recommended = listOf(series(3, "Ozark")),
                    recentlyAdded = emptyList(),
                    genreRows = emptyList(),
                    onResume = {},
                    onOpenSeries = {},
                )
            }
        }

        assertThat(topOf("Favourites")).isGreaterThan(topOf("Continue watching"))
        assertThat(topOf("Recommended for you")).isGreaterThan(topOf("Favourites"))
    }

    // No Favourites: with them, Recently added is below the fold and never laid out.
    @Test
    fun `Shows recommends series above Recently added`() {
        compose.setTvContent {
            ScreenFrame {
                SeriesShelves(
                    resume = resume,
                    favourites = emptyList(),
                    recommended = listOf(series(3, "Ozark")),
                    recentlyAdded = listOf(series(2, "Slow Horses")),
                    genreRows = emptyList(),
                    onResume = {},
                    onOpenSeries = {},
                )
            }
        }

        assertThat(topOf("Recommended for you")).isLessThan(topOf("Recently added"))
        compose.onNodeWithText("Ozark").requestFocus()
        compose.waitForIdle()
        captureScreen("shows_recommended")
    }

    @Test
    fun `Shows has no Recommended row when there is nothing to recommend`() {
        compose.setTvContent {
            ScreenFrame {
                SeriesShelves(
                    resume = resume,
                    favourites = emptyList(),
                    recommended = emptyList(),
                    recentlyAdded = listOf(series(2, "Slow Horses")),
                    genreRows = emptyList(),
                    onResume = {},
                    onOpenSeries = {},
                )
            }
        }

        compose.onNodeWithText("Recommended for you").assertDoesNotExist()
    }

    @Test
    fun `pressing select on a recommended series opens it`() {
        var opened: Series? = null
        val ozark = series(3, "Ozark")
        compose.setTvContent {
            ScreenFrame {
                SeriesShelves(
                    resume = emptyList(),
                    favourites = emptyList(),
                    recommended = listOf(ozark),
                    recentlyAdded = emptyList(),
                    genreRows = emptyList(),
                    onResume = {},
                    onOpenSeries = { opened = it },
                )
            }
        }

        compose.onNodeWithText("Ozark").requestFocus()
        compose.onNodeWithText("Ozark").performKeyInput { pressKey(Key.DirectionCenter) }

        assertThat(opened).isEqualTo(ozark)
    }

    @Test
    fun `a recommended series the viewer is on stays under the highlight until they leave the row`() {
        var recommended by mutableStateOf(listOf(series(3, "Ozark"), series(4, "Dark")))
        compose.setTvContent {
            ScreenFrame {
                SeriesShelves(
                    resume = emptyList(),
                    favourites = emptyList(),
                    recommended = recommended,
                    recentlyAdded = listOf(series(2, "Slow Horses")),
                    genreRows = emptyList(),
                    onResume = {},
                    onOpenSeries = {},
                )
            }
        }
        compose.onNodeWithText("Ozark").requestFocus()

        recommended = listOf(series(4, "Dark"))
        compose.waitForIdle()

        compose.onNodeWithText("Ozark").assertIsFocused()

        compose.onNodeWithText("Slow Horses").requestFocus()
        compose.waitForIdle()
        // Back up, since the row below goes to the top and takes Recommended off the screen.
        backUpToRecommended("Slow Horses")

        compose.onNodeWithText("Ozark").assertDoesNotExist()
        compose.onNodeWithText("Dark").assertExists()
    }

    private fun backUpToRecommended(from: String) {
        compose.onNodeWithText(from).performKeyInput { pressKey(Key.DirectionUp) }
        compose.mainClock.advanceTimeBy(1_000)
        compose.waitForIdle()
    }

    @Test
    fun `a Recommended row left with nothing stays until the viewer leaves it`() {
        var recommended by mutableStateOf(listOf(series(3, "Ozark")))
        compose.setTvContent {
            ScreenFrame {
                SeriesShelves(
                    resume = emptyList(),
                    favourites = emptyList(),
                    recommended = recommended,
                    recentlyAdded = listOf(series(2, "Slow Horses")),
                    genreRows = emptyList(),
                    onResume = {},
                    onOpenSeries = {},
                )
            }
        }
        compose.onNodeWithText("Ozark").requestFocus()

        recommended = emptyList()
        compose.waitForIdle()

        compose.onNodeWithText("Ozark").assertIsFocused()

        compose.onNodeWithText("Slow Horses").requestFocus()
        compose.waitForIdle()

        compose.onNodeWithText("Recommended for you").assertDoesNotExist()
    }

    @Test
    fun `a Recommended row without the highlight changes straight away`() {
        var recommended by mutableStateOf(listOf(series(3, "Ozark")))
        compose.setTvContent {
            ScreenFrame {
                SeriesShelves(
                    resume = emptyList(),
                    favourites = emptyList(),
                    recommended = recommended,
                    recentlyAdded = listOf(series(2, "Slow Horses")),
                    genreRows = emptyList(),
                    onResume = {},
                    onOpenSeries = {},
                )
            }
        }
        compose.onNodeWithText("Slow Horses").requestFocus()

        recommended = listOf(series(4, "Dark"))
        compose.waitForIdle()
        backUpToRecommended("Slow Horses")

        compose.onNodeWithText("Ozark").assertDoesNotExist()
        compose.onNodeWithText("Dark").assertExists()
    }

    @Test
    fun `a recommended movie the viewer is on stays under the highlight until they leave the row`() {
        var recommended by mutableStateOf(listOf(movie(3, "Heat"), movie(4, "Ronin")))
        compose.setTvContent {
            ScreenFrame {
                MovieShelves(
                    resume = emptyList(),
                    favourites = emptyList(),
                    recommended = recommended,
                    recentlyAdded = listOf(movie(2, "Collateral")),
                    genreRows = emptyList(),
                    onResume = {},
                    onOpenMovie = {},
                )
            }
        }
        compose.onNodeWithText("Heat").requestFocus()

        recommended = listOf(movie(4, "Ronin"))
        compose.waitForIdle()

        compose.onNodeWithText("Heat").assertIsFocused()

        compose.onNodeWithText("Collateral").requestFocus()
        compose.waitForIdle()

        compose.onNodeWithText("Heat").assertDoesNotExist()
    }
}
