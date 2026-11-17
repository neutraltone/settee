/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv

import android.app.Application
import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.test.core.app.ApplicationProvider
import app.opentv.core.ServiceLocator
import app.opentv.ui.vod.MoviesScreen
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

    private fun setMovies() = compose.setTvContent {
        MoviesScreen(onOpenMovie = {}, onResume = {}, hasSources = true, isSyncing = false, viewModel = viewModel)
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

    private fun assertNone(text: String) {
        compose.waitForIdle()
        val found = compose.onAllNodesWithText(text, substring = true).fetchSemanticsNodes()
        check(found.isEmpty()) { "Didn't expect \"$text\" on screen" }
    }
}
