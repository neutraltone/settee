/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv

import app.opentv.data.model.Category
import app.opentv.data.model.Movie
import app.opentv.data.model.Series
import app.opentv.data.model.Source
import app.opentv.data.model.SourceKind
import app.opentv.data.model.StreamKind
import app.opentv.data.repo.movies
import app.opentv.ui.VodViewModel
import app.opentv.ui.browseKey
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** On a real clock, for Room's own threads. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class VodViewModelCatalogueTest {

    @get:Rule
    val testGraph = TestGraph()

    private val app = testGraph.app
    private val graph = testGraph.graph
    private val viewModel by lazy { testGraph.viewModel { VodViewModel(app, graph) } }

    private fun movie(id: Long, name: String, category: String? = "c", tmdbId: String? = "8363") = Movie(
        id = id,
        sourceId = 1,
        streamId = "$id",
        name = name,
        categoryId = category,
        posterUrl = null,
        rating = null,
        year = null,
        plot = null,
        durationSeconds = null,
        containerExtension = "mkv",
        streamUrl = "https://example.com/$id",
        tmdbId = tmdbId,
    )

    @Test
    fun `movies are loaded for a category, and none are held for the home rows`() = runBlocking {
        val films = Category("c", 1, "Films", StreamKind.MOVIE)
        graph.database.categories().upsertAll(listOf(films))
        graph.database.movies().upsertAll(listOf(movie(1, "One")))
        testGraph.launch { viewModel.movieGrid.collect {} }

        viewModel.selectMovieCategory(films.browseKey)
        testGraph.firstWhenIdle(viewModel.movieGrid) { it?.size == 1 }

        viewModel.selectMovieCategory(null)
        testGraph.firstWhenIdle(viewModel.movieGrid) { it == null }
        Unit
    }

    @Test
    fun `shows are loaded for a category, and none are held for the home rows`() = runBlocking {
        val shows = Category("c", 1, "Shows", StreamKind.SERIES)
        graph.database.categories().upsertAll(listOf(shows))
        graph.database.series().upsertAll(
            listOf(
                Series(
                    id = 1,
                    sourceId = 1,
                    seriesId = "1",
                    name = "One",
                    categoryId = "c",
                    posterUrl = null,
                    rating = null,
                    year = null,
                    plot = null,
                ),
            ),
        )
        testGraph.launch { viewModel.seriesGrid.collect {} }

        viewModel.selectSeriesCategory(shows.browseKey)
        testGraph.firstWhenIdle(viewModel.seriesGrid) { it?.size == 1 }

        viewModel.selectSeriesCategory(null)
        testGraph.firstWhenIdle(viewModel.seriesGrid) { it == null }
        Unit
    }

    @Test
    fun `a title whose other version has progress says it will resume`() = runBlocking {
        val fourK = movie(1, "4K-EN - Superbad (2007)")
        graph.database.movies().upsertAll(listOf(fourK, movie(2, "EN - Superbad (2007)")))
        // Robolectric lists no decoders, so here as on a 1080p Stick the row tagged 4K doesn't play.
        assertThat(viewModel.resumesPartway(fourK)).isFalse()

        graph.viewerLibrary.recordProgress("movie:2", positionMillis = 600_000, durationMillis = EPISODE_MILLIS)

        assertThat(viewModel.resumesPartway(fourK)).isTrue()
    }

    @Test
    fun `Search lists a film once, however many of its Versions match`(): Unit = runBlocking {
        graph.database.movies().upsertAll(
            listOf(
                movie(1, "NF - Superbad (2007)"),
                movie(2, "EN - Superbad (2007)"),
                movie(3, "EN - Superbad Too (2009)", tmdbId = "9999"),
            ),
        )
        // Grouped first: the search's clock, moved on below, would otherwise outrun the grouping's wait,
        // and lists show every row until the grouping comes.
        testGraph.firstWhenIdle(graph.titleIndex.movies) { it?.of(3) != null }
        testGraph.launch { viewModel.movieResults.collect {} }

        viewModel.setVodSearchQuery("Superbad")
        // Past the wait for typing to stop, and then whatever the search sets off.
        testGraph.advanceBy(SEARCH_SETTLED_MILLIS)
        val found = viewModel.movieResults.value

        assertThat(found.map { it.tmdbId }).containsExactly("8363", "9999")
    }

    @Test
    fun `Recently Added lists a film once, where its newest Version puts it`(): Unit = runBlocking {
        graph.database.movies().upsertAll(
            listOf(
                movie(1, "NF - Superbad (2007)").copy(addedMillis = 100),
                movie(2, "EN - Juno (2007)", tmdbId = "7326").copy(addedMillis = 200),
                movie(3, "EN - Superbad (2007)").copy(addedMillis = 300),
            ),
        )
        testGraph.launch { viewModel.recentlyAddedMovies.collect {} }

        val recent = testGraph.firstWhenIdle(viewModel.recentlyAddedMovies.filterNotNull()) { it.size == 2 }

        assertThat(recent.map { it.id }).containsExactly(3L, 2L).inOrder()
    }

    @Test
    fun `a person's page lists a film once`(): Unit = runBlocking {
        graph.database.movies().upsertAll(
            listOf(
                movie(1, "NF - Superbad (2007)").copy(cast = "Jonah Hill, Michael Cera"),
                movie(2, "EN - Superbad (2007)").copy(cast = "Jonah Hill, Michael Cera"),
            ),
        )

        assertThat(viewModel.titlesWithPerson("Jonah Hill")).hasSize(1)
    }

    @Test
    fun `a film's page shows it a Favourite when another of its Versions is starred`(): Unit = runBlocking {
        graph.database.sources().insert(
            Source(id = 1, name = "Provider", kind = SourceKind.XTREAM, url = "http://example.com"),
        )
        val netflix = movie(1, "NF - Superbad (2007)")
        val english = movie(2, "EN - Superbad (2007)")
        graph.database.movies().upsertAll(listOf(netflix, english))
        graph.viewerLibrary.favourites.set(netflix, true)

        val favourites = graph.viewerLibrary.favourites.movies().first()

        assertThat(viewModel.isFavourite(english, favourites)).isTrue()
    }
}

/** Longer than Search waits for typing to stop. */
private const val SEARCH_SETTLED_MILLIS = 2_000L
