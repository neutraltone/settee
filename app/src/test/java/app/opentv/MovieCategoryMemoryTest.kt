/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv

import android.app.Application
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.InputMode
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.LocalInputModeManager
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.requestFocus
import androidx.compose.ui.unit.dp
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

    /** Opens the Categories Sidebar from [fromCover]'s cover in For you, and picks [category]. */
    private fun pick(category: String, fromCover: String) {
        waitFor(fromCover)
        compose.onAllNodes(hasText(fromCover) and hasClickAction())[0].requestFocus()
        compose.onRoot().performKeyInput { pressKey(Key.DirectionLeft) }
        waitFor(category)
        compose.onNodeWithText(category).performClick()
        compose.waitUntilDoesNotExist(hasText(recentlyAdded), 5_000)
    }

    /** As a remote drives it: in touch mode nothing can take the Highlight. */
    @Composable
    private fun RemoteInput() {
        val inputMode = LocalInputModeManager.current
        SideEffect { inputMode.requestInputMode(InputMode.Keyboard) }
    }

    /** Where the rail is: the Highlight has to have somewhere to go to the left for Left to open the sidebar. */
    @Composable
    private fun RailStandIn() {
        Box(Modifier.size(48.dp).focusable())
    }

    private val recentlyAdded get() = app.getString(R.string.vod_recently_added)

    /** On the category's grid, not back on For you, whose rows would show the same title. */
    private fun assertOnGridOf(title: String) {
        compose.waitUntilAtLeastOneExists(hasText(title) and hasClickAction(), 5_000)
        compose.onNodeWithText(recentlyAdded).assertDoesNotExist()
    }

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
                        addedMillis = 1,
                    ),
                ),
            )
        }
        val restoration = StateRestorationTester(compose)
        restoration.setContent {
            SetteeTheme {
                RemoteInput()
                Row {
                    RailStandIn()
                    MoviesScreen(
                        onOpenMovie = {},
                        onResume = {},
                        hasSources = false,
                        isSyncing = false,
                        viewModel = viewModel,
                    )
                }
            }
        }
        pick("Movies 4K", fromCover = "Superbad")
        assertOnGridOf("Superbad")
        captureScreen("movies_category")

        restoration.emulateSavedInstanceStateRestore()

        assertOnGridOf("Superbad")
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
                        addedMillis = 1,
                    ),
                ),
            )
        }
        val restoration = StateRestorationTester(compose)
        restoration.setContent {
            SetteeTheme {
                RemoteInput()
                Row {
                    RailStandIn()
                    SeriesScreen(
                        onOpenSeries = {},
                        onResume = {},
                        hasSources = false,
                        isSyncing = false,
                        viewModel = viewModel,
                    )
                }
            }
        }
        pick("Shows 4K", fromCover = "House of the Dragon")
        assertOnGridOf("House of the Dragon")

        restoration.emulateSavedInstanceStateRestore()

        assertOnGridOf("House of the Dragon")
    }
}
