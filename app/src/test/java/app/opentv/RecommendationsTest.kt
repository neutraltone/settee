/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv

import androidx.test.core.app.ApplicationProvider
import app.opentv.data.model.Movie
import app.opentv.data.model.PlaybackPosition
import app.opentv.data.model.Series
import app.opentv.data.repo.ContinueWatchingCard
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

// Plain Application: OpenTvApp schedules WorkManager on start, which the database doesn't need.
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class RecommendationsTest {

    private val test = TestCatalog(ApplicationProvider.getApplicationContext())
    private val catalog = test.catalog
    private val library = test.viewerLibrary

    @After
    fun close() = test.close()

    private fun movie(id: Long, genre: String? = null, rating: Double? = null, added: Long = 0) = Movie(
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
        addedMillis = added,
        genre = genre,
    )

    private fun series(id: Long, genre: String? = null, rating: Double? = null, added: Long = 0) = Series(
        id = id,
        sourceId = 1,
        seriesId = "$id",
        name = "Series $id",
        categoryId = null,
        posterUrl = null,
        rating = rating,
        year = null,
        plot = null,
        addedMillis = added,
        genre = genre,
    )

    /** An episode of series [seriesId], its local id the same as the series' for readable keys. */
    private fun episodeOf(seriesId: Long) =
        testEpisode(1, 1).copy(id = seriesId, seriesId = "$seriesId", episodeId = "$seriesId")

    private suspend fun start(profileId: Long, mediaKey: String) {
        library.mergeSynced(
            PlaybackPosition(
                profileId,
                mediaKey,
                positionMillis = 600_000,
                durationMillis = 2_400_000,
                updatedAtMillis = 1,
            ),
        )
    }

    private suspend fun startSeries(seriesId: Long, profileId: Long = 1) {
        test.db.episodes().upsertAll(listOf(episodeOf(seriesId)))
        start(profileId, "ep:$seriesId")
    }

    private suspend fun recommendedMovies(all: List<Movie>, profileId: Long = 1, limit: Int = 30) =
        catalog.recommendedMoviesFrom(all, library.history.startedMovieIds(profileId), limit).map { it.id }

    private suspend fun recommendedSeries(all: List<Series>, profileId: Long = 1, limit: Int = 30) =
        catalog.recommendedSeriesFrom(
            all,
            library.history.startedSeries(profileId),
            library.history.dismissedSeries(profileId),
            limit,
        ).map { it.id }

    // ---- Movies ---------------------------------------------------------------------------------

    @Test
    fun `movies sharing the most genres with started ones come first, then by rating, then by date added`() = runTest {
        val all = listOf(
            movie(1, genre = "Drama, Crime"),
            movie(2, genre = "Drama", rating = 9.0),
            movie(3, genre = "Drama, Crime", rating = 5.0),
            movie(4, genre = "Drama, Crime", rating = 7.0, added = 10),
            movie(5, genre = "Drama, Crime", rating = 7.0, added = 20),
            movie(6, genre = "Comedy", rating = 10.0),
        )
        start(1, "movie:1")

        assertThat(recommendedMovies(all)).containsExactly(5L, 4L, 3L, 2L).inOrder()
    }

    @Test
    fun `with no history, movies go by top rating and then date added`() = runTest {
        val all = listOf(
            movie(1, rating = 7.0, added = 10),
            movie(2, rating = 9.0),
            movie(3, rating = 7.0, added = 20),
            movie(4, added = 99),
        )

        assertThat(recommendedMovies(all)).containsExactly(2L, 3L, 1L, 4L).inOrder()
    }

    @Test
    fun `started movies that carry no genre fall back to top rated without the started ones`() = runTest {
        val all = listOf(movie(1, rating = 10.0), movie(2, genre = "Drama", rating = 5.0), movie(3, rating = 8.0))
        start(1, "movie:1")

        assertThat(recommendedMovies(all)).containsExactly(3L, 2L).inOrder()
    }

    @Test
    fun `when nothing shares a started movie's genre, movies fall back to top rated`() = runTest {
        val all = listOf(movie(1, genre = "Drama"), movie(2, genre = "Comedy", rating = 5.0), movie(3, rating = 8.0))
        start(1, "movie:1")

        assertThat(recommendedMovies(all)).containsExactly(3L, 2L).inOrder()
    }

    @Test
    fun `a movie marked Unwatched can be recommended again and doesn't count towards the tally`() = runTest {
        val all = listOf(movie(1, genre = "Drama", rating = 1.0), movie(2, genre = "Comedy", rating = 9.0))
        start(1, "movie:1")
        library.marks.removeFromContinueWatching(ContinueWatchingCard.Movie(movieId = 1, progress = 0f))

        assertThat(recommendedMovies(all)).containsExactly(2L, 1L).inOrder()
    }

    @Test
    fun `movies stop at the limit`() = runTest {
        assertThat(recommendedMovies(listOf(movie(1, rating = 3.0), movie(2, rating = 2.0)), limit = 1))
            .containsExactly(1L)
    }

    // ---- Series ---------------------------------------------------------------------------------

    @Test
    fun `series sharing the most genres with started ones come first, then by rating, then by date added`() = runTest {
        val all = listOf(
            series(1, genre = "Drama, Crime"),
            series(2, genre = "Drama", rating = 9.0),
            series(3, genre = "Drama, Crime", rating = 5.0),
            series(4, genre = "Drama, Crime", rating = 7.0, added = 10),
            series(5, genre = "Drama, Crime", rating = 7.0, added = 20),
            series(6, genre = "Comedy", rating = 10.0),
        )
        startSeries(1)

        assertThat(recommendedSeries(all)).containsExactly(5L, 4L, 3L, 2L).inOrder()
    }

    @Test
    fun `with no history, series go by top rating and then date added`() = runTest {
        val all = listOf(
            series(1, rating = 7.0, added = 10),
            series(2, rating = 9.0),
            series(3, rating = 7.0, added = 20),
            series(4, added = 99),
        )

        assertThat(recommendedSeries(all)).containsExactly(2L, 3L, 1L, 4L).inOrder()
    }

    @Test
    fun `a Watched series is left out and counts towards the tally`() = runTest {
        val all = listOf(series(1, genre = "Drama"), series(2, genre = "Drama", rating = 1.0), series(3, rating = 9.0))
        test.db.episodes().upsertAll(listOf(episodeOf(1)))
        library.recordProgress("ep:1", positionMillis = 2_400_000, durationMillis = 2_400_000)

        assertThat(recommendedSeries(all)).containsExactly(2L)
    }

    @Test
    fun `a series whose only progress is marked Unwatched is recommended and isn't tallied`() = runTest {
        val all = listOf(series(1, genre = "Drama", rating = 9.0), series(2, genre = "Comedy", rating = 1.0))
        startSeries(1)
        library.marks.markUnwatched("ep:1")

        assertThat(recommendedSeries(all)).containsExactly(1L, 2L).inOrder()
    }

    @Test
    fun `a series removed from Continue Watching is left out and isn't tallied`() = runTest {
        val all = listOf(series(1, genre = "Drama", rating = 9.0), series(2, genre = "Comedy", rating = 1.0))
        startSeries(1)
        library.marks.removeFromContinueWatching(ContinueWatchingCard.Series(episodeOf(1), progress = 0f))

        assertThat(recommendedSeries(all)).containsExactly(2L)
    }

    @Test
    fun `a series removed from Continue Watching and then played again stays out and untallied`() = runTest {
        val all = listOf(series(1, genre = "Drama"), series(2, genre = "Drama", rating = 1.0), series(3, rating = 9.0))
        startSeries(1)
        library.marks.removeFromContinueWatching(ContinueWatchingCard.Series(episodeOf(1), progress = 0f))
        library.recordProgress("ep:1", positionMillis = 600_000, durationMillis = 2_400_000)

        assertThat(recommendedSeries(all)).containsExactly(3L, 2L).inOrder()
    }

    @Test
    fun `two Profiles get different rows from their own history`() = runTest {
        val all =
            listOf(
                series(1, genre = "Drama"),
                series(2, genre = "Comedy"),
                series(3, genre = "Drama"),
                series(4, genre = "Comedy"),
            )
        startSeries(1, profileId = 1)
        startSeries(2, profileId = 2)

        assertThat(recommendedSeries(all, profileId = 1)).containsExactly(3L)
        assertThat(recommendedSeries(all, profileId = 2)).containsExactly(4L)
    }

    @Test
    fun `series stop at the limit`() = runTest {
        assertThat(recommendedSeries(listOf(series(1, rating = 3.0), series(2, rating = 2.0)), limit = 1))
            .containsExactly(1L)
    }

    @Test
    fun `an empty library recommends nothing`() = runTest {
        assertThat(recommendedSeries(emptyList())).isEmpty()
    }
}
