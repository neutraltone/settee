/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import app.opentv.core.ServiceLocator
import app.opentv.data.model.Category
import app.opentv.data.model.Movie
import app.opentv.data.model.Series
import app.opentv.data.model.StreamKind
import app.opentv.data.repo.categoryGroupKey
import app.opentv.ui.VodViewModel
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** What Movies' and Shows' Categories Sidebar lists, and what a category's grid carries (#161). */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class VodCategoriesTest {

    private val app: Application = ApplicationProvider.getApplicationContext()
    private val graph = ServiceLocator.Graph(app)
    private val viewModel by lazy { VodViewModel(app, graph) }
    private val watching = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @Before
    fun mainDispatcher() = Dispatchers.setMain(UnconfinedTestDispatcher())

    @After
    fun close() {
        watching.cancel()
        graph.database.close()
        graph.settings.setHiddenCategories(emptySet())
        graph.settings.setHiddenUnlocked(false)
    }

    private fun <T> StateFlow<List<T>>.names(name: (T) -> String, until: (List<String>) -> Boolean) = runBlocking {
        watching.launch { collect {} }
        withTimeout(5_000) { first { until(it.map(name)) } }.map(name)
    }

    @Test
    fun `a Parental-Locked category is left out of the sidebar until the lock is lifted`() = runBlocking {
        val adult = Category("9", 1, "XXX | Adult", StreamKind.MOVIE, sortIndex = 1)
        val action = Category("2", 1, "Action", StreamKind.MOVIE, sortIndex = 0)
        graph.database.categories().upsertAll(
            listOf(
                adult,
                action,
                // Locked by name, so the same lock covers Shows' category of that name.
                Category("3", 1, "Adult", StreamKind.SERIES),
                Category("4", 1, "Drama", StreamKind.SERIES),
            ),
        )
        graph.settings.setCategoryLocked(categoryGroupKey(adult), locked = true)

        assertThat(viewModel.movieCategories.names(Category::name) { it.isNotEmpty() }).containsExactly("Action")
        assertThat(viewModel.seriesCategories.names(Category::name) { it.isNotEmpty() }).containsExactly("Drama")

        graph.settings.setHiddenUnlocked(true)
        assertThat(viewModel.movieCategories.names(Category::name) { it.size == 2 })
            .containsExactly("Action", "XXX | Adult").inOrder()
    }

    private fun movie(id: Long, name: String) = Movie(
        id = id,
        sourceId = 1,
        streamId = "$id",
        name = name,
        categoryId = "c",
        posterUrl = "https://example.com/$id.jpg",
        rating = null,
        year = null,
        plot = "A long synopsis the grid has no use for.",
        durationSeconds = null,
        containerExtension = "mkv",
        streamUrl = "https://example.com/$id",
    )

    @Test
    fun `a category's grid has a cover per film, its Qualities folded into one`() {
        runBlocking {
            graph.database.movies().upsertAll(
                listOf(movie(1, "Superbad 2007 4K"), movie(2, "Superbad 2007 HD"), movie(3, "Ronin 1998 HD")),
            )
        }
        viewModel.selectMovieCategory("c")

        val covers = viewModel.movieGrid.names({ "${it.id} ${it.title} ${it.qualities} ${it.posterUrl}" }) {
            it.size ==
                2
        }

        // Robolectric lists no decoders, so as on a 1080p Stick the 4K version isn't the one shown.
        assertThat(covers).containsExactly(
            "2 Superbad 2007 2 https://example.com/2.jpg",
            "3 Ronin 1998 1 https://example.com/3.jpg",
        )
    }

    @Test
    fun `a category's grid of shows has a cover per show`() {
        runBlocking {
            graph.database.series().upsertAll(
                listOf(
                    Series(1, 1, "1", "Shogun 4K", "c", "https://example.com/s.jpg", null, null, "A synopsis."),
                    Series(2, 1, "2", "Shogun HD", "c", null, null, null, null),
                ),
            )
        }
        viewModel.selectSeriesCategory("c")

        // Shows aren't folded by Quality: each is its own series, with its own episodes.
        assertThat(viewModel.seriesGrid.names({ "${it.id} ${it.posterUrl}" }) { it.size == 2 })
            .containsExactly("1 https://example.com/s.jpg", "2 null")
    }
}
