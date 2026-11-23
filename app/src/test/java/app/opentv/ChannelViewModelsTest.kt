/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv

import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.ui.test.junit4.createComposeRule
import app.opentv.data.model.Category
import app.opentv.data.model.Channel
import app.opentv.data.model.Source
import app.opentv.data.model.SourceKind
import app.opentv.data.model.StreamKind
import app.opentv.ui.ChannelsViewModel
import app.opentv.ui.HomeContent
import app.opentv.ui.HomeViewModel
import app.opentv.ui.channels.SearchContent
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Search and Home show Channels through the lineup: once each, and never from a locked category (#104). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = TV_1080P, application = android.app.Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ChannelViewModelsTest {

    private val compose = createComposeRule()

    @get:Rule
    val testGraph = TestGraph(compose)

    @get:Rule
    val artwork = PlaceholderArtwork()

    private val app = testGraph.app
    private val graph = testGraph.graph
    private val db = graph.database

    @Before
    fun setUp(): Unit = runBlocking {
        db.sources().insert(Source(id = 1, name = "Provider A", kind = SourceKind.XTREAM, url = "http://example.com"))
        db.sources().insert(Source(id = 2, name = "Provider B", kind = SourceKind.XTREAM, url = "http://example.org"))
        db.categories().upsertAll(
            listOf(
                Category(id = "10", sourceId = 1, name = "UK| News", kind = StreamKind.LIVE),
                Category(id = "66", sourceId = 2, name = "UK| Adult", kind = StreamKind.LIVE),
            ),
        )
    }

    @After
    fun close() {
        // The settings outlive the test; the lock mustn't leak into the next one.
        graph.settings.setHiddenCategories(emptySet())
    }

    /** Stores [names] as one provider's rows in [categoryId], cleaned the way an import cleans them. */
    private suspend fun seed(sourceId: Long, categoryId: String, vararg names: String) {
        val rows = names.mapIndexed { i, name ->
            Channel(
                sourceId = sourceId,
                streamId = "$sourceId-$i",
                name = name,
                categoryId = categoryId,
                logoUrl = null,
                epgChannelId = null,
                number = null,
                streamUrl = "http://example.com/live/u/p/$sourceId$i.ts",
            )
        }
        db.channels().replaceCatalogue(sourceId, rows, syncStamp = 1)
        graph.catalogRepository.renormalizeAll()
    }

    private suspend fun row(name: String): Channel = db.channels().everyRow().single { it.name == name }

    @Test
    fun `search leaves out channels in a locked category`() {
        runBlocking {
            seed(1, "10", "UK| Night News")
            seed(2, "66", "UK| Late Night")
        }
        graph.settings.setHiddenCategories(setOf("adult"))
        val viewModel = testGraph.viewModel { ChannelsViewModel(app, graph) }

        viewModel.setSearchQuery("night")
        // Drawn while the search's pause runs out on the main thread's clock.
        testGraph.launch { viewModel.searchResults.collect {} }
        testGraph.advanceBy(500)

        val results = testGraph.firstWhenIdle(viewModel.searchResults) { it.isNotEmpty() }
        assertThat(results.map { it.primary.name }).containsExactly("UK| Night News")
        compose.setTvContent {
            SearchContent(TextFieldState("night"), results, emptyList(), emptyList(), {}, {}, {}, {})
        }
        captureScreen("search_locked_category")
    }

    @Test
    fun `home shows a favourite channel once, whichever of its qualities were starred`() {
        runBlocking {
            seed(1, "10", "UK| ITV HD")
            seed(2, "20", "UK| ITV FHD")
            graph.viewerLibrary.favourites.set(row("UK| ITV HD"), true)
            graph.viewerLibrary.favourites.set(row("UK| ITV FHD"), true)
        }

        val home = testGraph.viewModel { HomeViewModel(app, graph) }
        val shelf = testGraph.firstWhenIdle(home.state.mapNotNull { it?.favouriteChannels }) { it.isNotEmpty() }

        assertThat(shelf.map { home -> home.channel.qualities.map { it.name } })
            .containsExactly(listOf("UK| ITV FHD", "UK| ITV HD"))
        compose.setTvContent {
            HomeContent(emptyList(), emptyList(), emptyList(), shelf, {}, {}, {}, {}, takeFocus = true)
        }
        captureScreen("home_favourite_channels_once")
    }

    @Test
    fun `home leaves a favourite in a locked category off its shelf`() {
        runBlocking {
            seed(1, "10", "UK| ITV HD")
            seed(2, "66", "UK| Late Night")
            graph.viewerLibrary.favourites.set(row("UK| ITV HD"), true)
            graph.viewerLibrary.favourites.set(row("UK| Late Night"), true)
        }
        graph.settings.setHiddenCategories(setOf("adult"))

        val home = testGraph.viewModel { HomeViewModel(app, graph) }
        val shelf = testGraph.firstWhenIdle(home.state.mapNotNull { it?.favouriteChannels }) { it.isNotEmpty() }

        assertThat(shelf.map { it.channel.primary.name }).containsExactly("UK| ITV HD")
    }

    @Test
    fun `Live TV's sidebar lists every provider's categories in its own order, without locked ones`() {
        runBlocking {
            db.categories().upsertAll(
                listOf(
                    Category(id = "10", sourceId = 1, name = "UK| News", kind = StreamKind.LIVE, sortIndex = 0),
                    Category(id = "11", sourceId = 1, name = "Films", kind = StreamKind.LIVE, sortIndex = 1),
                    Category(id = "20", sourceId = 2, name = "Sport", kind = StreamKind.LIVE, sortIndex = 0),
                    Category(id = "66", sourceId = 2, name = "UK| Adult", kind = StreamKind.LIVE, sortIndex = 1),
                    Category(id = "21", sourceId = 2, name = "Kids", kind = StreamKind.LIVE, sortIndex = 2),
                ),
            )
        }
        graph.settings.setHiddenCategories(setOf("adult"))
        val viewModel = testGraph.viewModel { ChannelsViewModel(app, graph) }

        val categories = testGraph.firstWhenIdle(viewModel.visibleCategoryGroups) { it.isNotEmpty() }

        assertThat(categories.map { it.label }).containsExactly("News", "Films", "Sport", "Kids").inOrder()
    }

    @Test
    fun `today's page in the guide ends where tomorrow's starts, at midnight`() {
        val viewModel = testGraph.viewModel { ChannelsViewModel(app, graph) }
        val todayStarts = testGraph.firstWhenIdle(viewModel.windowStartMillis)
        val todayEnds = testGraph.firstWhenIdle(viewModel.windowEndMillis)

        viewModel.nudgeGuideDay(1)
        val tomorrowStarts = testGraph.firstWhenIdle(viewModel.windowStartMillis) { it != todayStarts }

        assertThat(tomorrowStarts).isEqualTo(todayEnds)
    }
}
