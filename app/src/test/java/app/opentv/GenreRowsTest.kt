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
import app.opentv.data.repo.GenreGroup
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** For you's genre rows: unwatched titles, the genres the Profile watched most recently first. */
// Plain Application: OpenTvApp schedules WorkManager on start, which the database doesn't need.
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class GenreRowsTest {

    private var now = 1_000L
    private val test = TestCatalog(ApplicationProvider.getApplicationContext()) { now }
    private val catalog = test.catalog
    private val library = test.viewerLibrary

    @After
    fun close() = test.close()

    private fun movie(id: Long, genre: String?, rating: Double? = null, added: Long = 0) = Movie(
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

    private fun series(id: Long, genre: String?) = Series(
        id = id,
        sourceId = 1,
        seriesId = "$id",
        name = "Series $id",
        categoryId = null,
        posterUrl = null,
        rating = null,
        year = null,
        plot = null,
        genre = genre,
    )

    /** [count] movies of [genre], their ids from [from]. */
    private fun movies(genre: String, from: Long, count: Int = 8) = (from until from + count).map { movie(it, genre) }

    private fun seriesOf(genre: String, from: Long, count: Int = 8) =
        (from until from + count).map { series(it, genre) }

    private fun episodeOf(seriesId: Long) =
        testEpisode(1, 1).copy(id = seriesId, seriesId = "$seriesId", episodeId = "$seriesId")

    /** The Profile plays a little of [mediaKey] at [at]. */
    private suspend fun play(mediaKey: String, at: Long, profileId: Long = 1) {
        now = at
        library.mergeSynced(
            PlaybackPosition(
                profileId,
                mediaKey,
                positionMillis = 600_000,
                durationMillis = 2_400_000,
                updatedAtMillis = at,
            ),
        )
    }

    private suspend fun playSeries(seriesId: Long, at: Long) {
        test.db.episodes().upsertAll(listOf(episodeOf(seriesId)))
        play("ep:$seriesId", at)
    }

    private suspend fun movieRows(all: List<Movie>, profileId: Long = 1, at: Long = NOW): List<GenreGroup<Movie>> =
        catalog.moviesByGenreFrom(
            all,
            started = library.history.startedMovieIds(profileId),
            recent = library.history.recentMovieIds(profileId),
            now = at,
        )

    private suspend fun seriesRows(all: List<Series>): List<GenreGroup<Series>> = catalog.seriesByGenreFrom(
        all,
        started = library.history.startedSeries(1),
        dismissed = library.history.dismissedSeries(1),
        recent = library.history.recentSeries(1),
        now = NOW,
    )

    private fun List<GenreGroup<*>>.genres() = map { it.genre }

    @Test
    fun `every genre with enough unwatched titles gets a row, not only the biggest dozen`() = runTest {
        val all = (0 until 15).flatMap { movies("Genre $it", from = it * 100L) }

        assertThat(movieRows(all)).hasSize(15)
    }

    @Test
    fun `a genre with fewer than eight unwatched titles gets no row`() = runTest {
        val all = movies("Drama", from = 1) + movies("Comedy", from = 100, count = 7)

        assertThat(movieRows(all).genres()).containsExactly("Drama")
    }

    @Test
    fun `the genres of the titles watched last come first, newest first, then the biggest others`() = runTest {
        val all = movies("Drama", from = 100, count = 12) +
            movies("Comedy", from = 200, count = 10) +
            movies("Horror", from = 300, count = 9) +
            movies("Western", from = 400, count = 11) +
            listOf(movie(1, "Horror"), movie(2, "Comedy"))
        play("movie:1", at = 10)
        play("movie:2", at = 20)

        assertThat(movieRows(all).genres()).containsExactly("Comedy", "Horror", "Drama", "Western").inOrder()
    }

    @Test
    fun `a title with several genres puts them in the order the provider lists them`() = runTest {
        val all = movies("Drama", from = 100) + movies("Crime", from = 200) + listOf(movie(1, "Drama, Crime"))
        play("movie:1", at = 10)

        assertThat(movieRows(all).genres()).containsExactly("Drama", "Crime").inOrder()
    }

    @Test
    fun `only the last twenty titles watched order the rows`() = runTest {
        val watched = (1L..21L).map { movie(it, if (it == 1L) "Western" else "Drama") }
        val all = movies("Western", from = 100) + movies("Drama", from = 200) +
            movies("Comedy", from = 300, count = 20) + watched
        watched.forEach { play("movie:${it.id}", at = it.id) }

        assertThat(movieRows(all).genres()).containsExactly("Drama", "Comedy", "Western").inOrder()
    }

    @Test
    fun `a title the Profile has started or watched is in no row`() = runTest {
        val all = movies("Drama", from = 1, count = 10)
        play("movie:1", at = 10)
        library.recordProgress("movie:2", positionMillis = 2_400_000, durationMillis = 2_400_000)

        assertThat(movieRows(all).single().items.map { it.id }).containsNoneOf(1L, 2L)
    }

    @Test
    fun `a movie marked Unwatched is back in its row and doesn't order the rows`() = runTest {
        val all = movies("Drama", from = 100, count = 10) + movies("Comedy", from = 200) + listOf(movie(1, "Comedy"))
        play("movie:1", at = 10)
        library.marks.removeFromContinueWatching(ContinueWatchingCard.Movie(movieId = 1, progress = 0f))

        val rows = movieRows(all)

        assertThat(rows.genres()).containsExactly("Drama", "Comedy").inOrder()
        assertThat(rows[1].items.map { it.id }).contains(1L)
    }

    @Test
    fun `a row holds at most twenty titles`() = runTest {
        assertThat(movieRows(movies("Drama", from = 1, count = 30)).single().items).hasSize(20)
    }

    @Test
    fun `a row's titles go by rating with a bonus for being new, a rating of ten counting as average`() = runTest {
        val all = movies("Drama", from = 100, count = 4) + listOf(
            movie(1, "Drama", rating = 8.5, added = NOW - 2 * YEAR),
            movie(2, "Drama", rating = 7.5, added = NOW - DAY),
            movie(3, "Drama", rating = 10.0, added = NOW - 2 * YEAR),
            movie(4, "Drama", rating = 7.0, added = NOW - 2 * YEAR),
        )

        assertThat(movieRows(all).single().items.map { it.id }.take(4)).containsExactly(2L, 1L, 4L, 3L).inOrder()
    }

    @Test
    fun `two Profiles get their own order`() = runTest {
        val all = movies("Drama", from = 100) + movies("Comedy", from = 200) +
            listOf(movie(1, "Drama"), movie(2, "Comedy"))
        play("movie:1", at = 10, profileId = 1)
        play("movie:2", at = 10, profileId = 2)

        assertThat(movieRows(all, profileId = 1).genres()).containsExactly("Drama", "Comedy").inOrder()
        assertThat(movieRows(all, profileId = 2).genres()).containsExactly("Comedy", "Drama").inOrder()
    }

    @Test
    fun `shows are ordered by the show with the newest episode played`() = runTest {
        val all = seriesOf("Drama", from = 100) + seriesOf("Comedy", from = 200, count = 9) +
            listOf(series(1, "Drama"), series(2, "Comedy"))
        playSeries(1, at = 30)
        playSeries(2, at = 20)

        assertThat(seriesRows(all).genres()).containsExactly("Drama", "Comedy").inOrder()
    }

    @Test
    fun `a show removed from Continue Watching is in no row and doesn't order the rows`() = runTest {
        val all = seriesOf("Drama", from = 100, count = 9) +
            seriesOf("Comedy", from = 200) +
            listOf(series(1, "Comedy"))
        playSeries(1, at = 10)
        library.marks.removeFromContinueWatching(ContinueWatchingCard.Series(episodeOf(1), progress = 0f))

        val rows = seriesRows(all)

        assertThat(rows.genres()).containsExactly("Drama", "Comedy").inOrder()
        assertThat(rows.flatMap { it.items }.map { it.id }).doesNotContain(1L)
    }

    private companion object {
        const val DAY = 86_400_000L
        const val YEAR = 365 * DAY
        const val NOW = 10 * YEAR
    }
}
