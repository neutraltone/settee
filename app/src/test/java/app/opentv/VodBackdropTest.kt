/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import app.opentv.core.ServiceLocator
import app.opentv.data.model.Movie
import app.opentv.data.model.Series
import app.opentv.data.repo.CoverStatus
import app.opentv.ui.VodViewModel
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** What Movies' and Shows' backdrop shows for a title (#161). */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class VodBackdropTest {

    private val app: Application = ApplicationProvider.getApplicationContext()
    private val graph = ServiceLocator.Graph(app)
    private val viewModel by lazy { VodViewModel(app, graph) }

    @Before
    fun mainDispatcher() = Dispatchers.setMain(UnconfinedTestDispatcher())

    @After
    fun close() {
        graph.database.close()
        Dispatchers.resetMain()
    }

    private fun movie(backdrop: String?) = Movie(
        id = 1,
        sourceId = 1,
        streamId = "1",
        name = "Heat",
        categoryId = "c",
        posterUrl = "https://example.com/poster.jpg",
        rating = null,
        year = null,
        plot = "A heist.",
        durationSeconds = null,
        containerExtension = "mkv",
        streamUrl = "https://example.com/1",
        backdropUrl = backdrop,
    )

    @Test
    fun `a film with only a cover has no backdrop art, rather than its cover across the screen`() = runBlocking {
        graph.database.movies().upsertAll(listOf(movie(backdrop = null)))

        val shown = viewModel.movieBackdrop(1).toList().last()

        assertThat(shown?.title).isEqualTo("Heat")
        assertThat(shown?.imageUrl).isNull()
    }

    @Test
    fun `a film's backdrop is its wide art`() = runBlocking {
        graph.database.movies().upsertAll(listOf(movie(backdrop = "https://example.com/wide.jpg")))

        assertThat(viewModel.movieBackdrop(1).toList().last()?.imageUrl).isEqualTo("https://example.com/wide.jpg")
    }

    @Test
    fun `a show part way through an episode says so`() = runBlocking {
        graph.database.series().upsertAll(
            listOf(Series(7, 1, "s", "The Show", "c", null, null, null, "A show.")),
        )
        graph.database.episodes().upsertAll(listOf(testEpisode(2, 4)))
        graph.viewerLibrary.recordProgress("ep:24", positionMillis = 600_000, durationMillis = 2_400_000)

        val status = viewModel.seriesBackdrop(7).toList().last()?.status

        assertThat(status).isInstanceOf(CoverStatus.InProgress::class.java)
    }

    @Test
    fun `a film's backdrop carries its year, its first two genres and its running time`() = runBlocking {
        graph.database.movies().upsertAll(
            listOf(
                movie(backdrop = null).copy(year = 1995, genre = "Crime, Drama, Thriller", durationSeconds = 10_200),
            ),
        )

        val shown = viewModel.movieBackdrop(1).toList().last()

        assertThat(shown?.year).isEqualTo(1995)
        assertThat(shown?.genres).containsExactly("Crime", "Drama").inOrder()
        assertThat(shown?.runtimeSeconds).isEqualTo(10_200)
    }
}
