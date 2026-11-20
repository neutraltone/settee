/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv

import android.app.Application
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.test.core.app.ApplicationProvider
import app.opentv.data.model.Movie
import app.opentv.data.model.Series
import app.opentv.data.model.Source
import app.opentv.data.model.SourceKind
import app.opentv.ui.design.SKELETON_CARD
import app.opentv.ui.design.ScreenFrame
import app.opentv.ui.vod.CoverGrid
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
 * Movies and Shows while For you's rows are on their way (#581): the rows quick checks expect, held
 * by skeletons from the first frame, rather than nothing until every row has come. Every read of the
 * catalogue is held, as a big one is slow on a Stick.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = TV_1080P, application = android.app.Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@OptIn(ExperimentalTestApi::class)
class VodSkeletonTest {

    @get:Rule
    val compose = createComposeRule()

    private val app: Application = ApplicationProvider.getApplicationContext()
    private val catalogue = HeldCatalogue(app, "vod-skeleton-test")
    private val graph = catalogue.graph
    private val viewModels = TestViewModels()

    @Before
    fun setUp() = runBlocking {
        val db = graph.database
        db.sources().insert(Source(id = 1, name = "Provider", kind = SourceKind.XTREAM, url = "http://example.com"))
        db.movies().upsertAll(listOf(heat))
        db.series().upsertAll(listOf(severance))
        // Something played and a Favourite of each, so both tabs expect Continue Watching and Favourites.
        graph.viewerLibrary.recordProgress("movie:1", positionMillis = HALF, durationMillis = HALF * 2)
        graph.viewerLibrary.recordProgress("ep:5", positionMillis = HALF, durationMillis = HALF * 2)
        graph.viewerLibrary.favourites.set(heat, true)
        graph.viewerLibrary.favourites.set(severance, true)
        catalogue.hold()
    }

    @After
    fun tearDown() {
        catalogue.release()
        viewModels.clear()
        catalogue.close()
    }

    @Test
    fun `Movies holds places for Continue Watching and Favourites while they load`() {
        compose.setTvContent {
            MoviesScreen(
                onOpenMovie = {},
                onResume = {},
                providers = ProvidersCarry.NO_PROVIDER,
                isSyncing = false,
                viewModel = viewModels.vod(app, graph),
            )
        }
        compose.waitUntilAtLeastOneExists(hasTestTag(SKELETON_CARD), 5_000)

        compose.onNodeWithText("Continue watching").assertExists()
        compose.onNodeWithText("Favourites").assertExists()
        // Recently added's place is further down, under Recommended's, off the screen.
        assertThat(compose.onAllNodes(hasText("Loading movies")).fetchSemanticsNodes()).isEmpty()
        captureScreen("movies_skeleton")
    }

    @Test
    fun `Shows holds places for Continue Watching and Favourites while they load`() {
        compose.setTvContent {
            SeriesScreen(
                onOpenSeries = {},
                onResume = {},
                providers = ProvidersCarry.NO_PROVIDER,
                isSyncing = false,
                viewModel = viewModels.vod(app, graph),
            )
        }
        compose.waitUntilAtLeastOneExists(hasTestTag(SKELETON_CARD), 5_000)

        compose.onNodeWithText("Continue watching").assertExists()
        compose.onNodeWithText("Favourites").assertExists()
        // Recently added's place is further down, under Recommended's, off the screen.
        captureScreen("shows_skeleton")
    }

    @Test
    fun `a category whose covers are still loading shows a grid of skeleton covers, not a message`() {
        compose.setTvContent {
            ScreenFrame {
                CoverGrid(null, empty = "None", onHighlight = {}, onOpen = {}, onOptions = null)
            }
        }

        assertThat(compose.onAllNodesWithTag(SKELETON_CARD).fetchSemanticsNodes().size).isGreaterThan(1)
        assertThat(compose.onAllNodes(hasText("Loading movies")).fetchSemanticsNodes()).isEmpty()
    }

    private val heat = Movie(
        id = 1,
        sourceId = 1,
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
    )

    private val severance = Series(
        id = 2,
        sourceId = 1,
        seriesId = "2",
        name = "Severance",
        categoryId = null,
        posterUrl = null,
        rating = null,
        year = null,
        plot = null,
    )

    private companion object {
        const val HALF = 3_000_000L
    }
}
