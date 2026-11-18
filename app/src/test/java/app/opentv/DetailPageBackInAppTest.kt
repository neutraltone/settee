/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv

import android.app.Application
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.isFocused
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.core.app.ApplicationProvider
import app.opentv.PageBack.Companion.isClickable
import app.opentv.core.ServiceLocator
import app.opentv.data.model.Movie
import app.opentv.data.model.Series
import app.opentv.ui.vod.MovieDetailScreen
import app.opentv.ui.vod.SeriesDetailScreen
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
 * database each time it's built, its buttons wait for what they depend on, More like this is read after
 * it shows, and only then does the Highlight go back where the viewer left from. The database is read on
 * its own threads, so the tests wait for the Highlight rather than advancing the clock to it.
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

    private val page = PageBack(compose)

    private val app: Application = ApplicationProvider.getApplicationContext()
    private val panel = FakePanel()
    private val graph = ServiceLocator.Graph(app, http = panel.http)
    private val viewModels = TestViewModels()

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

    /** Other dramas, which More like this picks from by genre without TMDB. */
    private val otherFilms = (8L..10L).map { film.copy(id = it, streamId = "$it", name = "Film $it", cast = null) }

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

    private val otherShows = (8L..10L).map { show.copy(id = it, seriesId = "$it", name = "Show $it") }

    private fun waitForHighlightOn(text: String) =
        compose.waitUntilAtLeastOneExists(isClickable(text) and isFocused(), TIMEOUT_MILLIS)

    private fun showFilmPage() {
        runBlocking {
            graph.database.sources().insert(PANEL_SOURCE)
            graph.database.movies().upsertAll(listOf(film) + otherFilms)
        }
        val viewModel = viewModels.vod(app, graph)
        page.show { leave ->
            MovieDetailScreen(
                movieId = film.id,
                onBack = {},
                onPlay = {},
                onPlayAddonStream = {},
                onOpenMovie = { leave() },
                onOpenPerson = { leave() },
                viewModel = viewModel,
            )
        }
        waitForHighlightOn("Play")
    }

    private fun showShowPage() {
        val stored = (1..4).map { testEpisode(1, it).copy(sourceId = PANEL_SOURCE.id, seriesId = "7") }
        runBlocking {
            graph.database.sources().insert(PANEL_SOURCE)
            graph.database.series().upsertAll(listOf(show) + otherShows)
            graph.database.episodes().upsertAll(stored)
        }
        panel.replies["get_series_info:7"] = """
            {"episodes": {"1": [{"id": "1-1", "episode_num": 1, "title": "Episode 1"},
                                {"id": "1-2", "episode_num": 2, "title": "Episode 2"},
                                {"id": "1-3", "episode_num": 3, "title": "Episode 3"},
                                {"id": "1-4", "episode_num": 4, "title": "Episode 4"}]}}
        """.trimIndent()
        val viewModel = viewModels.vod(app, graph)
        page.show { leave ->
            SeriesDetailScreen(
                seriesId = show.id,
                onBack = {},
                onPlayEpisode = { leave() },
                onOpenPerson = {},
                onOpenSeries = { leave() },
                viewModel = viewModel,
            )
        }
        waitForHighlightOn("Play S1 E1")
    }

    @Test
    fun `Back from a person opened from a film's cast puts the highlight back on their name`() {
        showFilmPage()
        page.press(Key.DirectionDown)
        page.press(Key.DirectionRight)
        page.clickable("Second Billing").assertIsFocused()
        page.openHighlighted()

        page.back()

        waitForHighlightOn("Second Billing")
        page.clickable("Second Billing").assertIsDisplayed()
    }

    @Test
    fun `Back from a film opened from More like this puts the highlight back on its cover`() {
        showFilmPage()
        compose.waitUntilAtLeastOneExists(isClickable("Film 8"), TIMEOUT_MILLIS)
        page.press(Key.DirectionDown)
        page.press(Key.DirectionDown)
        page.press(Key.DirectionRight)
        val picked = page.highlightedText()
        page.openHighlighted()

        page.back()

        waitForHighlightOn(picked)
        page.clickable(picked).assertIsDisplayed()
    }

    @Test
    fun `Back from an episode played puts the highlight back on it`() {
        showShowPage()
        page.press(Key.DirectionDown)
        page.press(Key.DirectionDown)
        page.press(Key.DirectionDown)
        page.clickable("S1 E3").assertIsFocused()
        page.openHighlighted()

        page.back()

        waitForHighlightOn("S1 E3")
        page.clickable("S1 E3").assertIsDisplayed()
    }

    @Test
    fun `Back from a show opened from More like this puts the highlight back on its cover`() {
        showShowPage()
        compose.waitUntilAtLeastOneExists(isClickable("More like this"), TIMEOUT_MILLIS)
        page.press(Key.DirectionDown)
        page.press(Key.DirectionUp)
        page.press(Key.DirectionRight)
        page.press(Key.DirectionDown)
        page.press(Key.DirectionRight)
        val picked = page.highlightedText()
        page.openHighlighted()

        page.back()

        waitForHighlightOn(picked)
        page.clickable("More like this").assertIsSelected()
    }

    private companion object {
        const val TIMEOUT_MILLIS = 10_000L
    }
}
