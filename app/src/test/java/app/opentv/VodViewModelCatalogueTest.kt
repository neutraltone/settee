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
import app.opentv.ui.VodViewModel
import app.opentv.ui.browseKey
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** On a real clock, for Room's own threads. */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class VodViewModelCatalogueTest {

    private val app: Application = ApplicationProvider.getApplicationContext()
    private val graph = ServiceLocator.Graph(app)

    // Lazily, so that it's made on the main dispatcher set below.
    private val viewModel by lazy { VodViewModel(app, graph) }
    private val watching = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @Before
    fun mainDispatcher() = Dispatchers.setMain(UnconfinedTestDispatcher())

    @After
    fun close() {
        watching.cancel()
        graph.database.close()
        Dispatchers.resetMain()
    }

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
        watching.launch { viewModel.movieGrid.collect {} }

        viewModel.selectMovieCategory(films.browseKey)
        withTimeout(5_000) { viewModel.movieGrid.first { it?.size == 1 } }

        viewModel.selectMovieCategory(null)
        withTimeout(5_000) { viewModel.movieGrid.first { it == null } }
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
        watching.launch { viewModel.seriesGrid.collect {} }

        viewModel.selectSeriesCategory(shows.browseKey)
        withTimeout(5_000) { viewModel.seriesGrid.first { it?.size == 1 } }

        viewModel.selectSeriesCategory(null)
        withTimeout(5_000) { viewModel.seriesGrid.first { it == null } }
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
}
