/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.requestFocus
import app.opentv.data.model.Movie
import app.opentv.data.repo.CatalogueRefresh
import app.opentv.ui.MainShell
import app.opentv.ui.Tab
import app.opentv.ui.VodViewModel
import app.opentv.ui.visibleTabs
import app.opentv.ui.vod.MoviesScreen
import app.opentv.ui.vod.ProvidersCarry
import app.opentv.ui.vod.SeriesScreen
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Movies and Shows whose download failed with nothing stored say why and offer Try again, rather
 * than blaming the provider for sending nothing (#461). On a real clock, for Room's and the panel's
 * own threads.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = TV_1080P, application = android.app.Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@OptIn(ExperimentalTestApi::class)
class VodLoadFailedTest {

    private val compose = createAndroidComposeRule<ComponentActivity>()

    private val panel = FakePanel()

    @get:Rule
    val testGraph = TestGraph(compose, http = panel.http)

    @get:Rule
    val artwork = PlaceholderArtwork()

    private val app = testGraph.app
    private val graph = testGraph.graph
    private val viewModel by lazy { testGraph.viewModel { VodViewModel(app, graph) } }

    /** The providers Provider setup was pressed for. */
    private val edited = mutableListOf<Long>()

    @Before
    fun provider() = runBlocking<Unit> {
        graph.database.sources().insert(PANEL_SOURCE)
    }

    @After
    fun close() {
        graph.settings.forgetDatabaseRows()
    }

    private fun setMovies() = setTab(Tab.MOVIES)

    private fun setShows() = setTab(Tab.SHOWS)

    /** Movies' or Shows' [tab] open behind the rail, as the app draws it, with nothing highlighted yet. */
    private fun setTab(tab: Tab) {
        shown = tab
        compose.setTvContent { Tabs() }
    }

    /** The tab drawn: a test can move it, as picking another in the rail would. */
    private var shown by mutableStateOf(Tab.MOVIES)

    /** What the providers carry: a test can switch the Xtream login off, as Settings would. */
    private var providers by mutableStateOf(ProvidersCarry.MOVIES_AND_SHOWS)

    @Composable
    private fun Tabs() {
        val tab = shown
        MainShell(
            tabs = visibleTabs(liveEnabled = true, moviesEnabled = true, seriesEnabled = true),
            current = tab,
            onSelect = {},
            onOpenSearch = {},
            onOpenSettings = {},
            onOpenProfiles = {},
            activeProfileName = "Sam",
            onExit = {},
            modifier = Modifier.fillMaxSize(),
        ) { _, _, _ ->
            if (tab == Tab.MOVIES) {
                MoviesScreen(
                    onOpenMovie = {},
                    onResume = {},
                    providers = providers,
                    isSyncing = false,
                    onEditProvider = { edited += it },
                    viewModel = viewModel,
                )
            } else {
                SeriesScreen(
                    onOpenSeries = {},
                    onResume = {},
                    providers = providers,
                    isSyncing = false,
                    onEditProvider = { edited += it },
                    viewModel = viewModel,
                )
            }
        }
    }

    private fun press(key: Key) {
        compose.onRoot().performKeyInput { pressKey(key) }
        compose.waitForIdle()
    }

    /** OK on [tab] in the rail, which hands the Highlight to the tab after a moment. */
    private fun enter(tab: String) {
        compose.onNode(hasContentDescription(tab) or hasText(tab)).requestFocus()
        press(Key.DirectionCenter)
        compose.mainClock.advanceTimeBy(500)
        compose.waitForIdle()
    }

    private fun awaitText(text: String) = compose.onAllNodes(hasText(text, substring = true)).onFirst().assertExists()

    @Test
    fun `Movies whose download can't reach the provider says so and offers Try again`() {
        panel.replies["get_vod_streams"] = "!host"

        setMovies()

        // TV or device wording, whichever this configuration counts as.
        awaitText(UNREACHABLE)
        awaitText(app.getString(R.string.common_try_again))
        assertNone(app.getString(R.string.vod_no_movies_provider))
        captureScreen("movies_load_failed")
    }

    @Test
    fun `a build without Settee's Sentry key never offers Send report`() {
        // A failure that would offer it in a release build (ErrorReportsTest).
        panel.replies["get_vod_streams"] = "!503"

        setMovies()

        awaitText(app.getString(R.string.common_try_again))
        assertNone(app.getString(R.string.report_send))
    }

    @Test
    fun `Try again, highlighted on coming into Movies, shows the covers once the provider answers`() {
        panel.replies["get_vod_streams"] = "!host"
        setMovies()
        awaitText(app.getString(R.string.common_try_again))
        enter("Movies")
        compose.onNode(hasText(app.getString(R.string.common_try_again))).assertIsFocused()

        panel.replies["get_vod_streams"] = """[{"stream_id": 3, "name": "Drive", "added": "100"}]"""
        press(Key.DirectionCenter)

        awaitText("Drive")
        assertNone(UNREACHABLE)
    }

    @Test
    fun `Try again says it's loading while it asks, and says why again if it fails again`() {
        panel.replies["get_vod_streams"] = "!host"
        setMovies()
        awaitText(UNREACHABLE)
        enter("Movies")
        val asking = panel.hold("get_vod_streams")

        press(Key.DirectionCenter)

        compose.waitForIdle()
        assertThat(asking.reached.count).isEqualTo(0L)
        awaitText(app.getString(R.string.vod_loading_movies))
        assertNone(UNREACHABLE)
        asking.release()
        awaitText(UNREACHABLE)
        awaitText(app.getString(R.string.common_try_again))
    }

    @Test
    fun `Shows whose download can't reach the provider says so, and Try again shows them once it answers`() {
        panel.replies["get_series"] = "!host"
        setShows()
        awaitText(UNREACHABLE)
        assertNone(app.getString(R.string.vod_no_shows_provider))
        captureScreen("shows_load_failed")
        enter("Shows")
        compose.onNode(hasText(app.getString(R.string.common_try_again))).assertIsFocused()

        panel.replies["get_series"] = """[{"series_id": 7, "name": "Taskmaster"}]"""
        press(Key.DirectionCenter)

        awaitText("Taskmaster")
        assertNone(UNREACHABLE)
    }

    @Test
    fun `movies failing doesn't stop the shows that came, which Shows shows while Movies says why`() {
        panel.replies["get_vod_streams"] = "!host"
        panel.replies["get_series"] = """[{"series_id": 7, "name": "Taskmaster"}]"""
        setShows()

        awaitText("Taskmaster")
        assertNone(UNREACHABLE)

        shown = Tab.MOVIES

        awaitText(UNREACHABLE)
    }

    @Test
    fun `movies already stored show as they are when a new download fails`() {
        runBlocking { graph.database.movies().upsertAll(listOf(storedMovie)) }
        panel.replies["get_vod_streams"] = "!host"
        setMovies()

        awaitText("Heat")
        compose.waitForIdle()
        assertThat(panel.asked.contains("get_vod_streams") && !viewModel.vodLoading.value).isTrue()
        assertNone(UNREACHABLE)
        awaitText("Heat")
    }

    @Test
    fun `a failure from before never shows over stored movies, even while their rows are worked out`() {
        runBlocking {
            graph.database.movies().upsertAll(listOf(storedMovie))
            panel.replies["get_vod_streams"] = "!host"
            // Recorded before the tab opens, as Setup's or a background refresh's would be.
            graph.catalogueRefresh.refresh(CatalogueRefresh.Trigger.OpenedMoviesOrShows)
        }
        fun errorOnScreen() =
            compose.onAllNodes(hasText(UNREACHABLE, substring = true)).fetchSemanticsNodes().isNotEmpty()
        // Held while their rows are worked out, then let through.
        testGraph.hold()
        setMovies()

        compose.waitForIdle()
        var errorShown = errorOnScreen()
        testGraph.release()
        compose.onAllNodes(hasText("Heat")).onFirst().assertExists()
        errorShown = errorShown || errorOnScreen()

        assertThat(errorShown).isFalse()
    }

    @Test
    fun `a provider whose download worked but has no movies still says it hasn't sent any`() {
        panel.replies["get_series"] = """[{"series_id": 7, "name": "Taskmaster"}]"""
        setMovies()

        awaitText(app.getString(R.string.vod_no_movies_provider))
        assertNone(app.getString(R.string.common_try_again))
    }

    @Test
    fun `switching off the Xtream login whose movies failed says movies need one, not why they failed`() {
        panel.replies["get_vod_streams"] = "!host"
        setMovies()
        awaitText(UNREACHABLE)

        providers = ProvidersCarry.LIVE_ONLY

        awaitText(app.getString(R.string.vod_no_movies_xtream_only))
        assertNone(UNREACHABLE)
        assertNone(app.getString(R.string.common_try_again))
    }

    @Test
    fun `Provider setup opens the provider whose movies failed`() {
        panel.replies["get_vod_streams"] = "!host"
        setMovies()
        awaitText(app.getString(R.string.guide_edit_provider))

        compose.onNode(hasText(app.getString(R.string.guide_edit_provider))).performClick()

        assertThat(edited).containsExactly(PANEL_SOURCE.id)
    }

    private val storedMovie = Movie(
        id = 1,
        sourceId = PANEL_SOURCE.id,
        streamId = "1",
        name = "Heat",
        categoryId = null,
        posterUrl = null,
        rating = null,
        year = null,
        plot = null,
        durationSeconds = null,
        containerExtension = "mkv",
        streamUrl = "https://example.com/1",
        addedMillis = 1,
    )

    private fun assertNone(text: String) {
        compose.waitForIdle()
        val found = compose.onAllNodesWithText(text, substring = true).fetchSemanticsNodes()
        check(found.isEmpty()) { "Didn't expect \"$text\" on screen" }
    }
}

/** The start of what a provider that can't be found says, on a TV or not (core/ErrorWording). */
private const val UNREACHABLE = "Can't reach your provider."
