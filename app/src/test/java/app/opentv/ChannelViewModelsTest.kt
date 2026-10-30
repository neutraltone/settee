/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv

import android.app.Application
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.core.app.ApplicationProvider
import app.opentv.core.ServiceLocator
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Search and Home show Channels through the lineup: once each, and never from a locked category (#104). */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = TV_1080P, application = android.app.Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ChannelViewModelsTest {

    @get:Rule
    val compose = createComposeRule()

    @get:Rule
    val artwork = PlaceholderArtwork()

    private val app: Application = ApplicationProvider.getApplicationContext()
    private val graph = ServiceLocator.Graph(app)
    private val db = graph.database

    @Before
    fun setUp() = runTest {
        Dispatchers.setMain(UnconfinedTestDispatcher())
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
        db.close()
        Dispatchers.resetMain()
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

    private suspend fun row(name: String): Channel = db.channels().allForMatching().single { it.name == name }

    @Test
    fun `search leaves out channels in a locked category`() = runTest {
        seed(1, "10", "UK| Night News")
        seed(2, "66", "UK| Late Night")
        graph.settings.setHiddenCategories(setOf("adult"))
        val viewModel = ChannelsViewModel(app, graph)

        viewModel.setSearchQuery("night")

        val results = viewModel.searchResults.first { it.isNotEmpty() }
        assertThat(results.map { it.primary.name }).containsExactly("UK| Night News")
        compose.setTvContent {
            SearchContent(TextFieldState("night"), results, emptyList(), emptyList(), {}, {}, {}, {})
        }
        captureScreen("search_locked_category")
    }

    @Test
    fun `home shows a favourite channel once, whichever of its qualities were starred`() = runTest {
        seed(1, "10", "UK| ITV HD")
        seed(2, "20", "UK| ITV FHD")
        graph.viewerLibrary.favourites.set(row("UK| ITV HD"), true)
        graph.viewerLibrary.favourites.set(row("UK| ITV FHD"), true)

        val shelf = HomeViewModel(app, graph).state.mapNotNull { it?.favouriteChannels }.first { it.isNotEmpty() }

        assertThat(shelf.map { channel -> channel.qualities.map { it.name } })
            .containsExactly(listOf("UK| ITV FHD", "UK| ITV HD"))
        compose.setTvContent {
            HomeContent(emptyList(), emptyList(), emptyList(), shelf, {}, {}, {}, {}, takeFocus = true)
        }
        captureScreen("home_favourite_channels_once")
    }

    @Test
    fun `home leaves a favourite in a locked category off its shelf`() = runTest {
        seed(1, "10", "UK| ITV HD")
        seed(2, "66", "UK| Late Night")
        graph.viewerLibrary.favourites.set(row("UK| ITV HD"), true)
        graph.viewerLibrary.favourites.set(row("UK| Late Night"), true)
        graph.settings.setHiddenCategories(setOf("adult"))

        val shelf = HomeViewModel(app, graph).state.mapNotNull { it?.favouriteChannels }.first { it.isNotEmpty() }

        assertThat(shelf.map { it.primary.name }).containsExactly("UK| ITV HD")
    }
}
