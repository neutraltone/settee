/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv

import android.app.Application
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import app.opentv.core.ServiceLocator
import app.opentv.data.model.Category
import app.opentv.data.model.Movie
import app.opentv.data.model.Series
import app.opentv.data.model.StreamKind
import app.opentv.ui.VodViewModel
import app.opentv.ui.design.SetteeTheme
import app.opentv.ui.vod.MoviesScreen
import app.opentv.ui.vod.SeriesScreen
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Opening a title disposes the tab, and Back must land on the category it was opened from (#180). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = TV_1080P, application = android.app.Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@OptIn(ExperimentalTestApi::class)
class MovieCategoryMemoryTest {

    @get:Rule
    val compose = createComposeRule()

    private val app: Application = ApplicationProvider.getApplicationContext()
    private val graph = ServiceLocator.Graph(app)
    private val viewModel = VodViewModel(app, graph)

    @After
    fun close() = graph.database.close()

    private fun waitFor(text: String) = compose.waitUntilAtLeastOneExists(hasText(text), 5_000)

    @Test
    fun `Movies comes back on the category it was left on`() {
        runBlocking {
            graph.database.categories().upsertAll(listOf(Category("4k", 1, "Movies 4K", StreamKind.MOVIE)))
            graph.database.movies().upsertAll(
                listOf(
                    Movie(
                        id = 1,
                        sourceId = 1,
                        streamId = "1",
                        name = "Superbad",
                        categoryId = "4k",
                        posterUrl = null,
                        rating = null,
                        year = null,
                        plot = null,
                        durationSeconds = null,
                        containerExtension = "mkv",
                        streamUrl = "https://example.com/1",
                    ),
                ),
            )
        }
        val restoration = StateRestorationTester(compose)
        restoration.setContent {
            SetteeTheme {
                MoviesScreen(
                    onOpenMovie = {},
                    onResume = {},
                    onOpenSearch = {},
                    hasSources = false,
                    isSyncing = false,
                    viewModel = viewModel,
                )
            }
        }
        waitFor("Movies 4K")
        compose.onNodeWithText("Movies 4K").performClick()
        waitFor("Superbad")
        captureScreen("movies_category")

        restoration.emulateSavedInstanceStateRestore()

        waitFor("Superbad")
    }

    @Test
    fun `Shows comes back on the category it was left on`() {
        runBlocking {
            graph.database.categories().upsertAll(listOf(Category("4k", 1, "Shows 4K", StreamKind.SERIES)))
            graph.database.series().upsertAll(
                listOf(
                    Series(
                        id = 1,
                        sourceId = 1,
                        seriesId = "1",
                        name = "House of the Dragon",
                        categoryId = "4k",
                        posterUrl = null,
                        rating = null,
                        year = null,
                        plot = null,
                    ),
                ),
            )
        }
        val restoration = StateRestorationTester(compose)
        restoration.setContent {
            SetteeTheme {
                SeriesScreen(
                    onOpenSeries = {},
                    onResume = {},
                    onOpenSearch = {},
                    hasSources = false,
                    isSyncing = false,
                    viewModel = viewModel,
                )
            }
        }
        waitFor("Shows 4K")
        compose.onNodeWithText("Shows 4K").performClick()
        waitFor("House of the Dragon")

        restoration.emulateSavedInstanceStateRestore()

        waitFor("House of the Dragon")
    }
}
