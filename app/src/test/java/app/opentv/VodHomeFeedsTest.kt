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
import app.opentv.data.repo.ContinueWatchingCard
import app.opentv.ui.VodViewModel
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

// Plain Application: OpenTvApp schedules WorkManager on start, which the view model doesn't need.
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class VodHomeFeedsTest {

    private val app: Application = ApplicationProvider.getApplicationContext()
    private val graph = ServiceLocator.Graph(app)

    @Before
    fun mainDispatcher() = Dispatchers.setMain(UnconfinedTestDispatcher())

    @After
    fun close() {
        graph.database.close()
        Dispatchers.resetMain()
    }

    private fun movie(id: Long, rating: Double, genre: String? = null) = Movie(
        id = id,
        sourceId = 1,
        streamId = "$id",
        name = "Movie $id",
        categoryId = null,
        posterUrl = null,
        rating = rating,
        year = null,
        plot = null,
        durationSeconds = null,
        containerExtension = "mp4",
        streamUrl = "",
        genre = genre,
    )

    private fun series(id: Long, rating: Double, genre: String? = null) = Series(
        id = id,
        sourceId = 1,
        seriesId = "$id",
        name = "Series $id",
        categoryId = null,
        posterUrl = null,
        rating = rating,
        year = null,
        plot = null,
        genre = genre,
    )

    private fun episodeOf(seriesId: Long) =
        testEpisode(1, 1).copy(id = seriesId, seriesId = "$seriesId", episodeId = "$seriesId")

    private suspend fun openWithTwoMovies() = VodViewModel(app, graph).also {
        graph.database.movies().upsertAll(listOf(movie(1, rating = 9.0), movie(2, rating = 8.0)))
        it.loadHomeFeeds().join()
    }

    private suspend fun openWithTwoSeries() = VodViewModel(app, graph).also {
        graph.database.series().upsertAll(listOf(series(1, rating = 9.0), series(2, rating = 8.0)))
        graph.database.episodes().upsertAll(listOf(episodeOf(1)))
        it.loadHomeFeeds().join()
    }

    private fun VodViewModel.recommendedMovieIds() = recommendedMovies.value.map { it.id }

    private fun VodViewModel.recommendedSeriesIds() = recommendedSeries.value.map { it.id }

    @Test
    fun `a movie the Profile has started is gone from Recommended the next time Movies opens`() = runTest {
        val vod = openWithTwoMovies()
        assertThat(vod.recommendedMovieIds()).containsExactly(1L, 2L).inOrder()

        graph.viewerLibrary.recordProgress("movie:1", positionMillis = 600_000, durationMillis = 2_400_000)
        vod.loadHomeFeeds().join()

        assertThat(vod.recommendedMovieIds()).containsExactly(2L)
    }

    @Test
    fun `a series the Profile has started is gone from Recommended the next time Shows opens`() = runTest {
        val vod = openWithTwoSeries()
        assertThat(vod.recommendedSeriesIds()).containsExactly(1L, 2L).inOrder()

        graph.viewerLibrary.recordProgress("ep:1", positionMillis = 600_000, durationMillis = 2_400_000)
        vod.loadHomeFeeds().join()

        assertThat(vod.recommendedSeriesIds()).containsExactly(2L)
    }

    @Test
    fun `a series removed from Continue Watching with no progress left is gone the next time Shows opens`() = runTest {
        val vod = openWithTwoSeries()
        graph.viewerLibrary.recordProgress("ep:1", positionMillis = 600_000, durationMillis = 2_400_000)
        graph.viewerLibrary.marks.markUnwatched("ep:1")
        vod.loadHomeFeeds().join()
        assertThat(vod.recommendedSeriesIds()).containsExactly(1L, 2L).inOrder()

        graph.viewerLibrary.marks.removeFromContinueWatching(
            ContinueWatchingCard.Series(episodeOf(1), progress = null),
        )
        vod.loadHomeFeeds().join()

        assertThat(vod.recommendedSeriesIds()).containsExactly(2L)
    }

    // A new name isn't something the rows are ranked by, so it only shows if the library was read again.
    @Test
    fun `reopening with nothing changed doesn't read the library again`() = runTest {
        val vod = openWithTwoSeries()
        graph.database.movies().upsertAll(listOf(movie(1, rating = 9.0), movie(2, rating = 8.0)))
        vod.loadHomeFeeds().join()

        graph.database.movies().upsertAll(listOf(movie(1, rating = 9.0).copy(name = "Renamed")))
        graph.database.series().upsertAll(listOf(series(1, rating = 9.0).copy(name = "Renamed")))
        vod.loadHomeFeeds().join()

        assertThat(vod.recommendedMovies.value.map { it.name }).containsExactly("Movie 1", "Movie 2").inOrder()
        assertThat(vod.recommendedSeries.value.map { it.name }).containsExactly("Series 1", "Series 2").inOrder()
    }

    @Test
    fun `Recommended follows a refresh that swaps titles and leaves the catalogue the same size`() = runTest {
        val vod = openWithTwoSeries()

        graph.database.series().deleteForSource(1)
        graph.database.series().upsertAll(listOf(series(3, rating = 9.0), series(4, rating = 8.0)))
        vod.loadHomeFeeds().join()

        assertThat(vod.recommendedSeriesIds()).containsExactly(3L, 4L).inOrder()
    }

    @Test
    fun `Recommended follows a rating filled in later`() = runTest {
        val vod = openWithTwoSeries()

        graph.database.series().upsertAll(listOf(series(2, rating = 9.4)))
        vod.loadHomeFeeds().join()

        assertThat(vod.recommendedSeriesIds()).containsExactly(2L, 1L).inOrder()
    }

    @Test
    fun `the genre rows follow a genre filled in later`() = runTest {
        val vod = openWithTwoSeries()

        graph.database.series().upsertAll((3L..10L).map { series(it, rating = 8.0, genre = "Drama") })
        vod.loadHomeFeeds().join()

        assertThat(vod.seriesGenreRows.value.map { it.genre }).containsExactly("Drama")
    }

    @Test
    fun `starting a movie doesn't read the series library again`() = runTest {
        val vod = openWithTwoSeries()
        graph.database.movies().upsertAll(listOf(movie(1, rating = 9.0), movie(2, rating = 8.0)))
        vod.loadHomeFeeds().join()

        graph.database.series().upsertAll(listOf(series(1, rating = 9.0).copy(name = "Renamed")))
        graph.viewerLibrary.recordProgress("movie:1", positionMillis = 600_000, durationMillis = 2_400_000)
        vod.loadHomeFeeds().join()

        assertThat(vod.recommendedMovieIds()).containsExactly(2L)
        assertThat(vod.recommendedSeries.value.map { it.name }).containsExactly("Series 1", "Series 2").inOrder()
    }

    /** [count] movies of [genre], their ids from [from]. */
    private fun movies(genre: String, from: Long, count: Int = 9) =
        (from until from + count).map { movie(it, rating = 7.0, genre = genre) }

    @Test
    fun `a movie the Profile has started is gone from its genre row the next time Movies opens`() = runTest {
        val vod = VodViewModel(app, graph)
        graph.database.movies().upsertAll(movies("Drama", from = 1))
        vod.loadHomeFeeds().join()

        graph.viewerLibrary.recordProgress("movie:1", positionMillis = 600_000, durationMillis = 2_400_000)
        vod.loadHomeFeeds().join()

        assertThat(vod.movieGenreRows.value.single().items.map { it.id }).doesNotContain(1L)
    }

    @Test
    fun `the genre of the movie watched last moves to the top of the genre rows`() = runTest {
        val vod = VodViewModel(app, graph)
        graph.database.movies().upsertAll(movies("Drama", from = 100, count = 10) + movies("Comedy", from = 1))
        vod.loadHomeFeeds().join()
        assertThat(vod.movieGenreRows.value.map { it.genre }).containsExactly("Drama", "Comedy").inOrder()

        graph.viewerLibrary.recordProgress("movie:1", positionMillis = 600_000, durationMillis = 2_400_000)
        vod.loadHomeFeeds().join()

        assertThat(vod.movieGenreRows.value.map { it.genre }).containsExactly("Comedy", "Drama").inOrder()
    }

    @Test
    fun `the genre of the show watched last moves to the top of the genre rows`() = runTest {
        val vod = VodViewModel(app, graph)
        graph.database.series().upsertAll(
            (100L..109L).map { series(it, rating = 7.0, genre = "Drama") } +
                (1L..9L).map { series(it, rating = 7.0, genre = "Comedy") },
        )
        graph.database.episodes().upsertAll(listOf(episodeOf(1)))
        vod.loadHomeFeeds().join()

        graph.viewerLibrary.recordProgress("ep:1", positionMillis = 600_000, durationMillis = 2_400_000)
        vod.loadHomeFeeds().join()

        assertThat(vod.seriesGenreRows.value.map { it.genre }).containsExactly("Comedy", "Drama").inOrder()
    }

    // The new rating only arrives with the scan, so the row seen without the title and in its old
    // order can only have been drawn before the scan finished.
    @Test
    fun `a started series leaves Recommended before the library has been read again`() = runTest {
        val vod = openWithTwoSeries()
        graph.database.series().upsertAll(listOf(series(3, rating = 7.0)))
        vod.loadHomeFeeds().join()
        val seen = mutableListOf<List<Long>>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            vod.recommendedSeries.collect { row -> seen += row.map { it.id } }
        }

        graph.database.series().upsertAll(listOf(series(3, rating = 9.4)))
        graph.viewerLibrary.recordProgress("ep:1", positionMillis = 600_000, durationMillis = 2_400_000)
        vod.loadHomeFeeds().join()

        assertThat(seen).containsExactly(listOf(1L, 2L, 3L), listOf(2L, 3L), listOf(3L, 2L)).inOrder()
    }

    @Test
    fun `Recommended follows the catalogue when its size changes`() = runTest {
        val vod = openWithTwoSeries()

        graph.database.series().upsertAll(listOf(series(3, rating = 9.4)))
        vod.loadHomeFeeds().join()

        assertThat(vod.recommendedSeriesIds()).containsExactly(3L, 1L, 2L).inOrder()
    }
}
