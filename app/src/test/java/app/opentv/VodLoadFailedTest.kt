/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv

import android.app.Application
import android.os.Looper
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
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.requestFocus
import androidx.test.core.app.ApplicationProvider
import app.opentv.core.ServiceLocator
import app.opentv.data.model.Movie
import app.opentv.ui.MainShell
import app.opentv.ui.Tab
import app.opentv.ui.visibleTabs
import app.opentv.ui.vod.MoviesScreen
import app.opentv.ui.vod.SeriesScreen
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
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

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    @get:Rule
    val artwork = PlaceholderArtwork()

    private val app: Application = ApplicationProvider.getApplicationContext()
    private val panel = FakePanel()
    private val graph = ServiceLocator.Graph(app, http = panel.http)
    private val viewModels = TestViewModels()
    private val viewModel by lazy { viewModels.vod(app, graph) }

    /** The providers Provider setup was pressed for. */
    private val edited = mutableListOf<Long>()

    @Before
    fun provider() = runBlocking<Unit> {
        graph.database.sources().insert(PANEL_SOURCE)
    }

    @After
    fun close() {
        viewModels.clear()
        graph.settings.forgetDatabaseRows()
        graph.database.close()
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
                    hasSources = true,
                    isSyncing = false,
                    onEditProvider = { edited += it },
                    viewModel = viewModel,
                )
            } else {
                SeriesScreen(
                    onOpenSeries = {},
                    onResume = {},
                    hasSources = true,
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

    /** Waits for [text], running the main looper on each check, where the view model's work lands. */
    private fun awaitText(text: String) = compose.waitUntil(10_000) {
        shadowOf(Looper.getMainLooper()).idle()
        compose.onAllNodes(hasText(text, substring = true)).fetchSemanticsNodes().isNotEmpty()
    }

    @Test
    fun `Movies whose download can't reach the provider says so and offers Try again`() {
        panel.replies["get_vod_streams"] = "!host"

        setMovies()

        // TV or device wording, whichever this configuration counts as.
        awaitText("Can't reach your provider.")
        awaitText(app.getString(R.string.common_try_again))
        assertNone(app.getString(R.string.vod_no_movies_provider))
        captureScreen("movies_load_failed")
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
        assertNone("Can't reach your provider.")
    }

    @Test
    fun `Shows whose download can't reach the provider says so, and Try again shows them once it answers`() {
        panel.replies["get_series"] = "!host"
        setShows()
        awaitText("Can't reach your provider.")
        assertNone(app.getString(R.string.vod_no_shows_provider))
        captureScreen("shows_load_failed")
        enter("Shows")
        compose.onNode(hasText(app.getString(R.string.common_try_again))).assertIsFocused()

        panel.replies["get_series"] = """[{"series_id": 7, "name": "Taskmaster"}]"""
        press(Key.DirectionCenter)

        awaitText("Taskmaster")
        assertNone("Can't reach your provider.")
    }

    @Test
    fun `movies failing doesn't stop the shows that came, which Shows shows while Movies says why`() {
        panel.replies["get_vod_streams"] = "!host"
        panel.replies["get_series"] = """[{"series_id": 7, "name": "Taskmaster"}]"""
        setShows()

        awaitText("Taskmaster")
        assertNone("Can't reach your provider.")

        shown = Tab.MOVIES

        awaitText("Can't reach your provider.")
    }

    @Test
    fun `movies already stored show as they are when a new download fails`() {
        runBlocking { graph.database.movies().upsertAll(listOf(storedMovie)) }
        panel.replies["get_vod_streams"] = "!host"
        setMovies()

        awaitText("Heat")
        compose.waitUntil(10_000) {
            shadowOf(Looper.getMainLooper()).idle()
            panel.asked.contains("get_vod_streams") && !viewModel.vodLoading.value
        }
        assertNone("Can't reach your provider.")
        awaitText("Heat")
    }

    @Test
    fun `a provider whose download worked but has no movies still says it hasn't sent any`() {
        panel.replies["get_series"] = """[{"series_id": 7, "name": "Taskmaster"}]"""
        setMovies()

        awaitText(app.getString(R.string.vod_no_movies_provider))
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
