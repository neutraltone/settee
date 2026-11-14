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
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.test.core.app.ApplicationProvider
import app.opentv.core.ServiceLocator
import app.opentv.data.model.Movie
import app.opentv.data.model.Source
import app.opentv.data.model.SourceKind
import app.opentv.data.repo.MediaKey
import app.opentv.ui.HomeScreen
import app.opentv.ui.HomeViewModel
import app.opentv.ui.design.SetteeType
import app.opentv.ui.design.Text
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Back to Home draws its rows in its first frame, however long the screen over it was open, except
 * from a player, where it is built again and waits for rows that know what was just watched (#192).
 * Through the real [KeptShell].
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = TV_1080P, application = Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class HomeBackTest {

    @get:Rule
    val compose = createComposeRule()

    @get:Rule
    val artwork = PlaceholderArtwork()

    private val app: Application = ApplicationProvider.getApplicationContext()
    private val graph = ServiceLocator.Graph(app)
    private val db = graph.database
    private val viewModels = ViewModelsInTest()

    // The view model's clock, moved on by hand while Settings is open.
    private val scheduler = TestCoroutineScheduler()

    private lateinit var nav: NavHostController

    @Before
    fun setUp(): Unit = runBlocking {
        Dispatchers.setMain(UnconfinedTestDispatcher(scheduler))
        db.sources().insert(Source(id = 1, name = "Provider", kind = SourceKind.XTREAM, url = "http://example.com"))
        db.movies().upsertAll(listOf(superbad))
        graph.viewerLibrary.recordProgress("movie:1", positionMillis = 600_000, durationMillis = 6_000_000)
    }

    @After
    fun close() {
        runBlocking { viewModels.stopAll() }
        db.close()
        Dispatchers.resetMain()
    }

    private fun setApp() {
        val home = viewModels.track(HomeViewModel(app, graph))
        compose.setTvContent {
            nav = rememberNavController()
            KeptShell(
                nav,
                shell = {
                    // Tagged so a test sees the frame Home is back in, even before it has rows to draw.
                    // Full size, so it counts as displayed in a frame with nothing in it yet.
                    Box(Modifier.fillMaxSize().testTag(HOME)) {
                        HomeScreen(
                            onResume = {},
                            onOpenSeries = {},
                            onOpenMovie = {},
                            onPlayChannel = {},
                            onOpenLive = {},
                            takeFocus = true,
                            onNothingToShow = {},
                            viewModel = home,
                        )
                    }
                },
            ) {
                NavHost(nav, startDestination = Routes.MAIN) {
                    mainShellRoute()
                    composable(Routes.SETTINGS) { Text("Settings", SetteeType.Body) }
                    composable(Routes.VOD_PLAYER) { Text("Player", SetteeType.Body) }
                }
            }
            KeepHomeLive(nav, graph.homeKeptLive)
        }
    }

    private fun shown(text: String) =
        compose.onAllNodes(hasText(text, substring = true)).fetchSemanticsNodes().isNotEmpty()

    // Displayed rather than present: under Settings, Home is still built (KeptShell), just not placed.
    private fun homeShown() = runCatching { compose.onNodeWithTag(HOME).assertIsDisplayed() }.isSuccess

    /** Opens [route] over Home and stays there for longer than Home once kept its rows. */
    private fun awayAt(route: String, until: String) {
        compose.waitUntil(5_000) { shown("Superbad") }
        compose.runOnIdle { nav.navigate(route) }
        compose.waitUntil(5_000) { shown(until) && !homeShown() }
        scheduler.advanceTimeBy(15_000)
    }

    /** Presses Back and moves on a frame at a time until the frame Home is back in. */
    private fun backToHomesFirstFrame() {
        compose.mainClock.autoAdvance = false
        compose.runOnUiThread { nav.popBackStack() }
        repeat(MAX_FRAMES) {
            if (homeShown()) return
            compose.mainClock.advanceTimeByFrame()
        }
        error("Home never came back")
    }

    @Test
    fun `back from Settings after a long time there, Home's first frame has its rows`() {
        setApp()
        awayAt(Routes.SETTINGS, until = "Settings")

        backToHomesFirstFrame()

        compose.onNode(hasText("Superbad", substring = true)).assertExists()
    }

    @Test
    fun `back from a player after a long time there, Home waits for fresh rows rather than drawing old ones`() {
        setApp()
        awayAt(Routes.vodPlayer(MediaKey.Movie(1)), until = "Player")

        backToHomesFirstFrame()

        compose.onNode(hasText("Superbad", substring = true)).assertDoesNotExist()
        compose.mainClock.autoAdvance = true
        compose.waitUntil(5_000) { shown("Superbad") }
    }

    private val superbad = Movie(
        id = 1,
        sourceId = 1,
        streamId = "1",
        name = "EN - Superbad (2007)",
        categoryId = null,
        posterUrl = null,
        rating = null,
        year = 2007,
        plot = null,
        durationSeconds = null,
        containerExtension = "mkv",
        streamUrl = "https://example.com/1",
    )

    private companion object {
        const val HOME = "home"
        const val MAX_FRAMES = 10
    }
}
