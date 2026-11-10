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
import app.opentv.data.repo.TitleCover
import app.opentv.data.repo.categoryGroupKey
import app.opentv.ui.VodViewModel
import app.opentv.ui.browseKey
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
        // The settings first: changing them wakes the grid's flow, which mustn't find the database closed.
        graph.settings.setHiddenCategories(emptySet())
        graph.settings.setHiddenUnlocked(false)
        watching.cancel()
        graph.database.close()
    }

    private fun <T> StateFlow<List<T>?>.names(name: (T) -> String, until: (List<String>) -> Boolean) = runBlocking {
        watching.launch { collect {} }
        withTimeout(5_000) { first { it != null && until(it.map(name)) } }.orEmpty().map(name)
    }

    private val films = Category("c", 1, "Films", StreamKind.MOVIE)
    private val shows = Category("c", 1, "Shows", StreamKind.SERIES)

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

    private fun movie(id: Long, name: String, sourceId: Long = 1) = Movie(
        id = id,
        sourceId = sourceId,
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
            graph.database.categories().upsertAll(listOf(films))
            graph.database.movies().upsertAll(
                listOf(movie(1, "Superbad 2007 4K"), movie(2, "Superbad 2007 HD"), movie(3, "Ronin 1998 HD")),
            )
        }
        viewModel.selectMovieCategory(films.browseKey)

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
        runBlocking { graph.database.categories().upsertAll(listOf(shows)) }
        viewModel.selectSeriesCategory(shows.browseKey)

        // Shows aren't folded by Quality: each is its own series, with its own episodes.
        assertThat(viewModel.seriesGrid.names({ "${it.id} ${it.posterUrl}" }) { it.size == 2 })
            .containsExactly("1 https://example.com/s.jpg", "2 null")
    }

    @Test
    fun `a category's grid has only its own provider's films, when another provider uses the same id`() {
        runBlocking {
            graph.database.categories().upsertAll(listOf(films, Category("c", 2, "Films", StreamKind.MOVIE)))
            graph.database.movies().upsertAll(listOf(movie(1, "Heat 1995", sourceId = 1), movie(2, "Ronin 1998", 2)))
        }
        viewModel.selectMovieCategory(films.browseKey)

        assertThat(viewModel.movieGrid.names(TitleCover::title) { it.isNotEmpty() }).containsExactly("Heat 1995")
    }

    @Test
    fun `an empty category's grid is empty once it has loaded, not loading for ever`() {
        runBlocking { graph.database.categories().upsertAll(listOf(films)) }
        viewModel.selectMovieCategory(films.browseKey)

        assertThat(viewModel.movieGrid.names(TitleCover::title) { true }).isEmpty()
    }

    @Test
    fun `a Parental-Locked category saved from before the lock came back shows nothing`() {
        runBlocking {
            graph.database.categories().upsertAll(listOf(films))
            graph.database.movies().upsertAll(listOf(movie(1, "Heat 1995")))
        }
        graph.settings.setCategoryLocked(categoryGroupKey(films), locked = true)
        viewModel.selectMovieCategory(films.browseKey)

        assertThat(viewModel.movieGrid.names(TitleCover::title) { true }).isEmpty()
    }
}
