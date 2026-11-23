/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv

import android.view.KeyEvent
import android.view.View
import androidx.compose.foundation.text.BasicText
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isFocused
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.requestFocus
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import app.opentv.data.model.Channel
import app.opentv.data.model.Movie
import app.opentv.data.model.Series
import app.opentv.data.model.Source
import app.opentv.data.model.SourceKind
import app.opentv.ui.VodViewModel
import app.opentv.ui.channels.SearchScreen
import app.opentv.ui.options.ItemOptionsHost
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
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

    private val compose = createComposeRule()

    @get:Rule
    val testGraph = TestGraph(compose, name = "recent-searches-screen-test")

    @get:Rule
    val artwork = PlaceholderArtwork()

    private val graph = testGraph.graph
    private val recent get() = graph.viewerLibrary.recentSearches

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
        compose.onAllNodes(hasText(text)).onFirst().assertExists()
        return compose.onNodeWithText(text)
    }

    private fun shown() = runBlocking { recent.observe().first() }

    /** Types [text] in the box, and waits out the pause Search leaves for typing to stop. */
    private fun search(text: String) {
        device.type(text)
        testGraph.advanceBy(SEARCH_SETTLED_MILLIS)
    }

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

        search("bbc")
        val result = waitFor("BBC One")
        assertThat(shown()).isEmpty()

        result.performClick()

        compose.waitForIdle()
        assertThat(shown()).isEqualTo(listOf("bbc"))
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

        compose.waitForIdle()
        assertThat(shown()).isEqualTo(listOf("news"))
        compose.onAllNodes(hasText("news") and isFocused()).onFirst().assertExists()
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
        search("zebra")
        waitFor("Zebra Crossing")

        act { popBackStack() }
        // Before Search is back, so its first frame can't have them.
        compose.waitForIdle()
        assertThat(vod.movieResults.value).isEmpty()
        act { navigate(Routes.SEARCH) }
        compose.waitForIdle()

        compose.onNodeWithText("zebra").assertDoesNotExist()
        compose.onNodeWithText("Zebra Crossing").assertDoesNotExist()
    }

    @Test
    fun `back from a title, Search still has its movies, with no empty moment while they reload`() = withVod { vod ->
        showSearchInApp(vod)
        search("zebra")
        waitFor("Zebra Crossing")

        act { navigate(Routes.movieDetail(1)) }
        compose.onNodeWithText("Title").assertExists()
        // Covered, not left: the results Search comes back to are still there.
        assertThat(vod.movieResults.value.map { it.name }).containsExactly("Zebra Crossing")

        act { popBackStack() }

        compose.onNodeWithText("Zebra Crossing").assertExists()
        captureScreen("search_back_from_title")
    }

    // Search is built again under a title page or the player, and used to put the highlight back in
    // the box, so the viewer pressed Down through the results again (#431).
    @Test
    fun `back from a film opened in the results, the highlight is on that film, not the box`() = withVod { vod ->
        showSearchInApp(vod)
        search("zebra")

        openFirstResultAndComeBack("Zebra Crossing")

        assertBackOn("Zebra Crossing")
    }

    @Test
    fun `back from a show opened in the results, the highlight is on that show, not the box`() =
        withVod(movies = emptyList(), series = listOf(series("Zebra Stripes"))) { vod ->
            showSearchInApp(vod)
            search("zebra")

            openFirstResultAndComeBack("Zebra Stripes")

            assertBackOn("Zebra Stripes")
            captureScreen("search_back_on_result")
        }

    @Test
    fun `back from a channel played from the results, the highlight is on that channel, not the box`() =
        withVod(movies = emptyList()) { vod ->
            addChannel("Quokka News")
            showSearchInApp(vod)
            search("quokka")

            openFirstResultAndComeBack("Quokka News")

            assertBackOn("Quokka News")
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
            testGraph.advanceBy(SEARCH_SETTLED_MILLIS)

            assertThat(vod.movieResults.value.map { it.name }).containsExactly("Zebra Crossing")
        }

    private lateinit var nav: NavHostController

    private fun act(block: NavHostController.() -> Unit) = compose.runOnIdle { nav.block() }

    /** Search in a NavHost as the app has it, over Home, with the pages and player its results open. */
    private fun showSearchInApp(vod: VodViewModel) {
        compose.setTvContent {
            view = LocalView.current
            nav = rememberNavController()
            ItemOptionsHost {
                NavHost(nav, startDestination = Routes.MAIN) {
                    composable(Routes.MAIN) { BasicText("Home") }
                    composable(Routes.SEARCH) {
                        SearchScreen(
                            onPlayChannel = { nav.navigate(Routes.player(it.id)) },
                            onPlayMovie = { nav.navigate(Routes.movieDetail(it.id)) },
                            onOpenSeries = { nav.navigate(Routes.seriesDetail(it.id)) },
                            onBack = { nav.popBackStack() },
                            vodViewModel = vod,
                        )
                    }
                    composable(Routes.MOVIE_DETAIL) { BasicText("Title") }
                    composable(Routes.SERIES_DETAIL) { BasicText("Show page") }
                    composable(Routes.PLAYER) { BasicText("Player") }
                }
            }
        }
        act { navigate(Routes.SEARCH) }
        compose.waitForIdle()
    }

    /** Runs [block] with a VodViewModel over the test's graph, its database holding [movies] and [series]. */
    private fun withVod(
        movies: List<Movie> = listOf(movie("Zebra Crossing")),
        series: List<Series> = emptyList(),
        block: (VodViewModel) -> Unit,
    ) {
        runBlocking {
            graph.database.sources().insert(
                Source(id = 1, name = "Provider", kind = SourceKind.XTREAM, url = "http://example.com"),
            )
            graph.database.movies().upsertAll(movies)
            graph.database.series().upsertAll(series)
        }
        block(testGraph.track(VodViewModel(testGraph.app, graph)))
    }

    /** Opens the result [name], the first in the list, and comes back to Search as Back does. */
    private fun openFirstResultAndComeBack(name: String) {
        waitFor(name)
        device.remote(KeyEvent.KEYCODE_DPAD_DOWN)
        compose.onNode(hasText(name) and hasClickAction()).assertIsFocused()
        device.remote(KeyEvent.KEYCODE_DPAD_CENTER)
        compose.waitForIdle()
        compose.onNode(hasText(name) and hasClickAction()).assertDoesNotExist()

        act { popBackStack() }
    }

    private fun assertBackOn(name: String) {
        compose.onAllNodes(hasText(name) and hasClickAction() and isFocused()).onFirst().assertExists()
        assertThat(device.highlighted()).isFalse()
    }

    // The provider is withVod's.
    private fun addChannel(name: String) = runBlocking {
        graph.database.channels().replaceCatalogue(1, listOf(channel(name)), syncStamp = 1)
        graph.catalogRepository.renormalizeAll()
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

    private fun series(name: String) = Series(
        sourceId = 1,
        seriesId = name,
        name = name,
        categoryId = null,
        posterUrl = null,
        rating = null,
        year = null,
        plot = null,
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
}

/** Longer than Search waits for typing to stop (SEARCH_PAUSE_MILLIS). */
private const val SEARCH_SETTLED_MILLIS = 500L
