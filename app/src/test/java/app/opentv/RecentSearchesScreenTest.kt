/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv

import android.app.Application
import android.view.View
import androidx.compose.foundation.text.BasicText
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isFocused
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.requestFocus
import androidx.lifecycle.viewModelScope
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import app.opentv.core.ServiceLocator
import app.opentv.data.db.OpenTvDatabase
import app.opentv.data.model.Channel
import app.opentv.data.model.Movie
import app.opentv.data.model.Source
import app.opentv.data.model.SourceKind
import app.opentv.ui.VodViewModel
import app.opentv.ui.channels.SearchScreen
import app.opentv.ui.options.ItemOptionsHost
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The search screen with its real view models, options panel and database, where
 * [SearchContentTest] fakes them: saving when a result opens, and Remove from the panel.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = TV_1080P, application = android.app.Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@OptIn(ExperimentalTestApi::class)
class RecentSearchesScreenTest {

    @get:Rule
    val compose = createComposeRule()

    @get:Rule
    val artwork = PlaceholderArtwork()

    private val graph = ServiceLocator.get(ApplicationProvider.getApplicationContext<Application>())
    private val recent get() = graph.viewerLibrary.recentSearches

    // The graph is process-wide, so its database outlives each test.
    @Before
    fun startEmpty() = runBlocking { recent.clear() }

    private lateinit var view: View
    private val device = DeviceKeyboard(compose) { view }

    private fun show() {
        compose.setTvContent {
            view = LocalView.current
            ItemOptionsHost {
                SearchScreen(onPlayChannel = {}, onPlayMovie = {}, onOpenSeries = {}, onBack = {})
            }
        }
    }

    private fun waitFor(text: String): SemanticsNodeInteraction {
        compose.waitUntilAtLeastOneExists(hasText(text), TIMEOUT_MILLIS)
        return compose.onNodeWithText(text)
    }

    private fun shown() = runBlocking { recent.observe().first() }

    @Test
    fun `opening a result saves the search, and typing alone doesn't`() {
        runBlocking {
            graph.database.sources().insert(
                Source(id = 1, name = "Provider", kind = SourceKind.XTREAM, url = "http://example.com"),
            )
            graph.database.channels().replaceCatalogue(1, listOf(channel("BBC One")), syncStamp = 1)
            graph.catalogRepository.renormalizeAll()
        }
        show()

        device.type("bbc")
        val result = waitFor("BBC One")
        assertThat(shown()).isEmpty()

        result.performClick()

        compose.waitUntil(TIMEOUT_MILLIS) { shown() == listOf("bbc") }
    }

    @Test
    fun `Remove in the panel opened with Menu removes the Recent Search`() {
        runBlocking {
            recent.save("news")
            recent.save("bbc")
        }
        show()

        waitFor("bbc").requestFocus().performKeyInput { pressKey(Key.Menu) }
        waitFor("Remove").performClick()

        compose.waitUntil(TIMEOUT_MILLIS) { shown() == listOf("news") }
        // The database answers before the screen: the list reaches it through a flow on another
        // thread, which waitForIdle doesn't know about, and only then does the row take the highlight.
        compose.waitUntilAtLeastOneExists(hasText("news") and isFocused(), TIMEOUT_MILLIS)
    }

    @Test
    fun `holding Select opens the same panel`() {
        runBlocking { recent.save("bbc") }
        show()

        waitFor("bbc").requestFocus().performKeyInput {
            keyDown(Key.DirectionCenter)
            advanceEventTime(1_000)
            keyUp(Key.DirectionCenter)
        }

        waitFor("Remove")
    }

    // Search now shares the app's one VodViewModel, which outlives each visit, where it used to get a new one.
    @Test
    fun `Search opened again starts empty, without the last visit's movies`() = withVod { vod ->
        showSearchInApp(vod)
        device.type("zebra")
        waitFor("Zebra Crossing")

        act { popBackStack() }
        // Before Search is back, so its first frame can't have them. waitUntil alone doesn't
        // recompose, so Search would never leave; waitForIdle each time round does.
        compose.waitUntil(TIMEOUT_MILLIS) {
            compose.waitForIdle()
            vod.movieResults.value.isEmpty()
        }
        act { navigate(Routes.SEARCH) }
        compose.waitForIdle()

        compose.onNodeWithText("zebra").assertDoesNotExist()
        compose.onNodeWithText("Zebra Crossing").assertDoesNotExist()
    }

    @Test
    fun `back from a title, Search still has its movies, with no empty moment while they reload`() = withVod { vod ->
        showSearchInApp(vod)
        device.type("zebra")
        waitFor("Zebra Crossing")

        act { navigate(Routes.movieDetail(1)) }
        compose.onNodeWithText("Title").assertExists()
        // Covered, not left: the results Search comes back to are still there.
        assertThat(vod.movieResults.value.map { it.name }).containsExactly("Zebra Crossing")

        act { popBackStack() }

        compose.onNodeWithText("Zebra Crossing").assertExists()
        captureScreen("search_back_from_title")
    }

    @Test
    fun `Search left and opened again at once keeps the new visit's movies as the old one finishes leaving`() =
        withVod { vod ->
            showSearchInApp(vod)
            // Frame by frame, so the new visit can search while the old one is still on its way out.
            compose.mainClock.autoAdvance = false
            act {
                popBackStack()
                navigate(Routes.SEARCH)
            }
            // A few frames: the new visit is up and has read its empty box, well inside the transition.
            repeat(5) { compose.mainClock.advanceTimeByFrame() }
            vod.setVodSearchQuery("zebra")

            compose.mainClock.autoAdvance = true
            compose.waitUntil(TIMEOUT_MILLIS) {
                compose.waitForIdle()
                vod.movieResults.value.isNotEmpty()
            }
            compose.waitForIdle()

            assertThat(vod.movieResults.value.map { it.name }).containsExactly("Zebra Crossing")
        }

    private lateinit var nav: NavHostController

    private fun act(block: NavHostController.() -> Unit) = compose.runOnIdle { nav.block() }

    /** Search in a NavHost as the app has it, over Home, with a title page it can open. */
    private fun showSearchInApp(vod: VodViewModel) {
        compose.setTvContent {
            view = LocalView.current
            nav = rememberNavController()
            ItemOptionsHost {
                NavHost(nav, startDestination = Routes.MAIN) {
                    composable(Routes.MAIN) { BasicText("Home") }
                    composable(Routes.SEARCH) {
                        SearchScreen(
                            onPlayChannel = {},
                            onPlayMovie = {},
                            onOpenSeries = {},
                            onBack = { nav.popBackStack() },
                            vodViewModel = vod,
                        )
                    }
                    composable(Routes.MOVIE_DETAIL) { BasicText("Title") }
                }
            }
        }
        act { navigate(Routes.SEARCH) }
        compose.waitForIdle()
    }

    /**
     * Runs [block] with a VodViewModel over a graph and database of its own holding one film, so the
     * app's shared one, which outlives each test, doesn't gain a film every run.
     */
    private fun withVod(block: (VodViewModel) -> Unit) {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val vodGraph = ServiceLocator.Graph(
            app,
            settingsFile = "search-vod-test",
            openDatabase = {
                Room.inMemoryDatabaseBuilder(app, OpenTvDatabase::class.java).allowMainThreadQueries().build()
            },
        )
        runBlocking {
            vodGraph.database.sources().insert(
                Source(id = 1, name = "Provider", kind = SourceKind.XTREAM, url = "http://example.com"),
            )
            vodGraph.database.movies().upsertAll(listOf(movie("Zebra Crossing")))
        }
        val vod = VodViewModel(app, vodGraph)
        try {
            block(vod)
        } finally {
            // Nothing clears a model built outside a ViewModelStore, so stop its flows by hand.
            vod.viewModelScope.cancel()
            vodGraph.database.close()
        }
    }

    private fun channel(name: String) = Channel(
        sourceId = 1,
        streamId = name,
        name = name,
        categoryId = null,
        logoUrl = null,
        epgChannelId = null,
        number = null,
        streamUrl = "https://example.com/$name.ts",
    )

    private fun movie(name: String) = Movie(
        sourceId = 1,
        streamId = name,
        name = name,
        categoryId = null,
        posterUrl = null,
        rating = null,
        year = null,
        plot = null,
        durationSeconds = null,
        containerExtension = "mp4",
        streamUrl = "https://example.com/$name.mp4",
    )

    private companion object {
        const val TIMEOUT_MILLIS = 10_000L
    }
}
