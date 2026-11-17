/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv

import android.app.Application
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isFocused
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.test.core.app.ApplicationProvider
import app.opentv.core.ServiceLocator
import app.opentv.data.model.Movie
import app.opentv.data.model.Series
import app.opentv.ui.vod.MovieDetailScreen
import app.opentv.ui.vod.SeriesDetailScreen
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Back to a film's or a show's page with its real view model (#429): the page is read again from the
 * database each time it's built, its buttons wait for what they depend on, and only then does the
 * Highlight go back where the viewer left from.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = TV_1080P, application = android.app.Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@OptIn(ExperimentalTestApi::class)
class DetailPageBackInAppTest {

    @get:Rule
    val compose = createComposeRule()

    @get:Rule
    val artwork = PlaceholderArtwork()

    private val app: Application = ApplicationProvider.getApplicationContext()
    private val panel = FakePanel()
    private val graph = ServiceLocator.Graph(app, http = panel.http)
    private val viewModels = TestViewModels()

    private lateinit var nav: NavHostController

    @After
    fun close() {
        viewModels.clear()
        graph.database.close()
    }

    private val film = Movie(
        id = 7,
        sourceId = PANEL_SOURCE.id,
        streamId = "7",
        name = "The Quiet Road",
        categoryId = null,
        posterUrl = null,
        rating = null,
        year = 2021,
        plot = "A long drive home.",
        durationSeconds = 112 * 60,
        containerExtension = "mkv",
        streamUrl = "https://example.com/m.mkv",
        genre = "Drama",
        cast = "Somebody Famous, Second Billing, Third Billing",
    )

    private val show = Series(
        id = 7,
        sourceId = PANEL_SOURCE.id,
        seriesId = "7",
        name = "The Long Way Round",
        categoryId = null,
        posterUrl = null,
        rating = null,
        year = 2024,
        plot = null,
        genre = "Drama",
    )

    private fun settle() {
        compose.waitForIdle()
        compose.mainClock.advanceTimeBy(500)
        compose.waitForIdle()
    }

    private fun press(key: Key) {
        compose.onNode(isFocused()).performKeyInput { pressKey(key) }
        settle()
    }

    private fun waitForFocus(matcher: SemanticsMatcher) =
        compose.waitUntilAtLeastOneExists(matcher and isFocused(), TIMEOUT_MILLIS)

    private fun openHighlighted() {
        press(Key.DirectionCenter)
        compose.runOnIdle { assertThat(nav.currentDestination?.route).isEqualTo("elsewhere") }
    }

    private fun backToPage() {
        compose.runOnIdle { nav.popBackStack() }
        settle()
    }

    private fun clickable(text: String) = hasText(text) and hasClickAction()

    @Test
    fun `Back from a person opened from a film's cast puts the highlight back on their name`() {
        runBlocking {
            graph.database.sources().insert(PANEL_SOURCE)
            graph.database.movies().upsertAll(listOf(film))
        }
        val viewModel = viewModels.vod(app, graph)
        compose.setTvContent {
            nav = rememberNavController()
            NavHost(nav, startDestination = "film") {
                composable("film") {
                    MovieDetailScreen(
                        movieId = film.id,
                        onPlay = {},
                        onPlayAddonStream = {},
                        onOpenMovie = {},
                        onOpenPerson = { nav.navigate("elsewhere") },
                        viewModel = viewModel,
                    )
                }
                composable("elsewhere") { Box(Modifier.fillMaxSize()) }
            }
        }
        waitForFocus(clickable("Play"))
        press(Key.DirectionDown)
        press(Key.DirectionRight)
        compose.onNode(clickable("Second Billing")).assertIsFocused()
        openHighlighted()

        backToPage()

        waitForFocus(clickable("Second Billing"))
        compose.onNode(clickable("Second Billing")).assertIsDisplayed()
    }

    @Test
    fun `Back from an episode played puts the highlight back on it`() {
        val stored = (1..4).map { testEpisode(1, it).copy(sourceId = PANEL_SOURCE.id, seriesId = "7") }
        runBlocking {
            graph.database.sources().insert(PANEL_SOURCE)
            graph.database.series().upsertAll(listOf(show))
            graph.database.episodes().upsertAll(stored)
        }
        panel.replies["get_series_info:7"] = """
            {"episodes": {"1": [{"id": "1-1", "episode_num": 1, "title": "Episode 1"},
                                {"id": "1-2", "episode_num": 2, "title": "Episode 2"},
                                {"id": "1-3", "episode_num": 3, "title": "Episode 3"},
                                {"id": "1-4", "episode_num": 4, "title": "Episode 4"}]}}
        """.trimIndent()
        val viewModel = viewModels.vod(app, graph)
        compose.setTvContent {
            nav = rememberNavController()
            NavHost(nav, startDestination = "show") {
                composable("show") {
                    SeriesDetailScreen(
                        seriesId = show.id,
                        onPlayEpisode = { nav.navigate("elsewhere") },
                        onOpenPerson = {},
                        viewModel = viewModel,
                    )
                }
                composable("elsewhere") { Box(Modifier.fillMaxSize()) }
            }
        }
        waitForFocus(clickable("Play S1 E1"))
        press(Key.DirectionDown)
        press(Key.DirectionDown)
        press(Key.DirectionDown)
        compose.onNode(clickable("S1 E3")).assertIsFocused()
        openHighlighted()

        backToPage()

        waitForFocus(clickable("S1 E3"))
        compose.onNode(clickable("S1 E3")).assertIsDisplayed()
    }

    private companion object {
        const val TIMEOUT_MILLIS = 10_000L
    }
}
