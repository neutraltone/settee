/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv

import androidx.test.core.app.ApplicationProvider
import app.opentv.data.model.Movie
import app.opentv.data.model.Series
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The films a film's page offers under More like this, and the shows a show's page offers. */
// Plain Application: OpenTvApp schedules WorkManager on start, which the database doesn't need.
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class MoreLikeThisTest {

    private val test = TestCatalog(ApplicationProvider.getApplicationContext())
    private val db = test.db

    @After
    fun close() {
        test.close()
    }

    private fun movie(
        name: String,
        genre: String? = null,
        rating: Double? = null,
        sourceId: Long = 1,
        tmdbId: String? = null,
    ) = Movie(
        sourceId = sourceId,
        streamId = name,
        name = name,
        categoryId = null,
        posterUrl = null,
        rating = rating,
        year = null,
        plot = null,
        durationSeconds = null,
        containerExtension = "mp4",
        streamUrl = "",
        genre = genre,
        tmdbId = tmdbId,
    )

    /** [this], with the TMDB ids TMDB recommends for it stored, in its order. */
    private fun Movie.recommending(ids: String) = copy(tmdbRecommendations = ids)

    private fun Series.recommending(ids: String) = copy(tmdbRecommendations = ids)

    private suspend fun stored(name: String) = db.movies().all().single { it.name == name }

    @Test
    fun `films sharing the film's genre come first, best-rated first, and never the film itself`() = runTest {
        db.movies().upsertAll(
            listOf(
                movie("The Quiet Road", genre = "Drama, Road", rating = 7.0),
                movie("Long Night", genre = "Drama", rating = 6.0),
                movie("Harbour Lights", genre = "Romance, Drama", rating = 8.5),
                movie("Laser Squad", genre = "Action", rating = 9.9),
            ),
        )

        val similar = test.catalog.moreLikeThis(stored("The Quiet Road"))

        assertThat(similar.map { it.name }).containsExactly("Harbour Lights", "Long Night").inOrder()
    }

    @Test
    fun `a film from the same provider comes before a better-rated one from another`() = runTest {
        db.movies().upsertAll(
            listOf(
                movie("The Quiet Road", genre = "Drama"),
                movie("Elsewhere", genre = "Drama", rating = 9.0, sourceId = 2),
                movie("Here", genre = "Drama", rating = 5.0),
            ),
        )

        val similar = test.catalog.moreLikeThis(stored("The Quiet Road"))

        assertThat(similar.map { it.name }).containsExactly("Here", "Elsewhere").inOrder()
    }

    @Test
    fun `a film with no genre is offered others from its category, best-rated first`() = runTest {
        db.movies().upsertAll(
            listOf(
                movie("Untitled").copy(categoryId = "7"),
                movie("Same shelf", rating = 6.0).copy(categoryId = "7"),
                movie("Same shelf, better", rating = 8.0).copy(categoryId = "7"),
                movie("Other shelf", rating = 9.0).copy(categoryId = "9"),
            ),
        )

        val similar = test.catalog.moreLikeThis(stored("Untitled"))

        assertThat(similar.map { it.name }).containsExactly("Same shelf, better", "Same shelf").inOrder()
    }

    @Test
    fun `a 10 out of 10 counts as average, since it's nearly always a handful of votes`() = runTest {
        db.movies().upsertAll(
            listOf(
                movie("The Quiet Road", genre = "Drama"),
                movie("Perfect Ten", genre = "Drama", rating = 10.0),
                movie("Solid", genre = "Drama", rating = 7.5),
                movie("Unrated", genre = "Drama"),
            ),
        )

        val similar = test.catalog.moreLikeThis(stored("The Quiet Road"))

        assertThat(similar.first().name).isEqualTo("Solid")
    }

    @Test
    fun `a film the provider added lately gets a lift over an older, slightly better-rated one`() = runTest {
        val now = 365 * 86_400_000L
        db.movies().upsertAll(
            listOf(
                movie("The Quiet Road", genre = "Drama"),
                movie("Older", genre = "Drama", rating = 7.5),
                movie("Fresh", genre = "Drama", rating = 6.5).copy(addedMillis = now),
            ),
        )

        val similar = test.catalog.moreLikeThis(stored("The Quiet Road"), now = now)

        assertThat(similar.map { it.name }).containsExactly("Fresh", "Older").inOrder()
    }

    @Test
    fun `a film sharing more of the film's genres comes before one sharing fewer`() = runTest {
        db.movies().upsertAll(
            listOf(
                movie("The Quiet Road", genre = "Drama, Crime"),
                movie("Just drama", genre = "Drama", rating = 8.0),
                movie("Crime drama", genre = "Crime|Drama", rating = 6.0),
                movie("Just crime", genre = "Crime", rating = 9.0),
            ),
        )

        val similar = test.catalog.moreLikeThis(stored("The Quiet Road"))

        assertThat(similar.first().name).isEqualTo("Crime drama")
        assertThat(similar.map { it.name }).containsAtLeast("Just drama", "Just crime")
    }

    @Test
    fun `a genre only matches whole, so Drama doesn't bring in Melodrama`() = runTest {
        db.movies().upsertAll(
            listOf(
                movie("The Quiet Road", genre = "Drama"),
                movie("Weepie", genre = "Melodrama", rating = 8.0),
                movie("Long Night", genre = "Drama", rating = 6.0),
            ),
        )

        val similar = test.catalog.moreLikeThis(stored("The Quiet Road"))

        assertThat(similar.map { it.name }).containsExactly("Long Night")
    }

    // ---- TMDB's recommendations -----------------------------------------------------------------

    @Test
    fun `with TMDB's recommendations, the films it recommends come first, its own provider's, then TMDB's order`() =
        runTest {
            val catalog = test.catalogWithTmdb()
            db.movies().upsertAll(
                listOf(
                    movie("The Quiet Road", genre = "Drama", tmdbId = "1").recommending("30,10,20"),
                    movie("Ten", tmdbId = "10"),
                    movie("Twenty", tmdbId = "20", rating = 9.0),
                    movie("Thirty, elsewhere", tmdbId = "30", sourceId = 2),
                    movie("Long Night", genre = "Drama", rating = 9.5),
                ),
            )

            val similar = catalog.moreLikeThis(stored("The Quiet Road"))

            assertThat(similar.map { it.name })
                .containsExactly("Ten", "Twenty", "Thirty, elsewhere", "Long Night").inOrder()
        }

    @Test
    fun `when TMDB's recommendations match fewer films than the row holds, genre matches fill it, none twice`() =
        runTest {
            val catalog = test.catalogWithTmdb()
            db.movies().upsertAll(
                listOf(
                    movie("The Quiet Road", genre = "Drama", tmdbId = "1").recommending("10,99"),
                    movie("Ten", genre = "Drama", tmdbId = "10"),
                    movie("Long Night", genre = "Drama", rating = 6.0),
                    movie("Harbour Lights", genre = "Drama", rating = 8.0),
                ),
            )

            val similar = catalog.moreLikeThis(stored("The Quiet Road"), limit = 2)

            assertThat(similar.map { it.name }).containsExactly("Ten", "Harbour Lights").inOrder()
        }

    @Test
    fun `a film TMDB recommends that the library has twice gets one cover, its own provider's`() = runTest {
        val catalog = test.catalogWithTmdb()
        db.movies().upsertAll(
            listOf(
                movie("The Quiet Road", tmdbId = "1").recommending("10"),
                movie("Ten, elsewhere", tmdbId = "10", sourceId = 2),
                movie("Ten", tmdbId = "10"),
            ),
        )

        val similar = catalog.moreLikeThis(stored("The Quiet Road"))

        assertThat(similar.map { it.name }).containsExactly("Ten")
    }

    @Test
    fun `the film's own other Versions are never suggested, even when TMDB recommends them`() = runTest {
        val catalog = test.catalogWithTmdb()
        db.movies().upsertAll(
            listOf(
                movie("The Quiet Road", genre = "Drama", tmdbId = "5").recommending("5,10"),
                movie("The Quiet Road 4K", genre = "Drama", tmdbId = "5", sourceId = 2),
                movie("Ten", tmdbId = "10"),
            ),
        )

        val similar = catalog.moreLikeThis(stored("The Quiet Road"))

        assertThat(similar.map { it.name }).containsExactly("Ten")
    }

    // ---- A show's page --------------------------------------------------------------------------

    private fun series(
        name: String,
        genre: String? = null,
        rating: Double? = null,
        sourceId: Long = 1,
        tmdbId: String? = null,
    ) = Series(
        sourceId = sourceId,
        seriesId = name,
        name = name,
        categoryId = null,
        posterUrl = null,
        rating = rating,
        year = null,
        plot = null,
        genre = genre,
        tmdbId = tmdbId,
    )

    private suspend fun storedShow(name: String) = db.series().all().single { it.name == name }

    @Test
    fun `a show is offered shows sharing its genres, its provider's first, then most shared, never itself`() = runTest {
        db.series().upsertAll(
            listOf(
                series("Harbour", genre = "Drama, Crime"),
                series("Elsewhere", genre = "Drama, Crime", rating = 9.0, sourceId = 2),
                series("Just drama", genre = "Drama", rating = 8.0),
                series("Crime drama", genre = "Crime|Drama", rating = 6.0),
                series("Cartoons", genre = "Animation", rating = 9.9),
            ),
        )

        val similar = test.catalog.moreLikeThis(storedShow("Harbour"))

        assertThat(similar.map { it.name }).containsExactly("Crime drama", "Just drama", "Elsewhere").inOrder()
    }

    @Test
    fun `a show with no genre is offered others from its category`() = runTest {
        db.series().upsertAll(
            listOf(
                series("Untitled").copy(categoryId = "7"),
                series("Same shelf").copy(categoryId = "7"),
                series("Other shelf").copy(categoryId = "9"),
            ),
        )

        val similar = test.catalog.moreLikeThis(storedShow("Untitled"))

        assertThat(similar.map { it.name }).containsExactly("Same shelf")
    }

    @Test
    fun `with TMDB's recommendations, a show is offered the shows it recommends first, then genre matches`() = runTest {
        val catalog = test.catalogWithTmdb()
        db.series().upsertAll(
            listOf(
                series("Harbour", genre = "Drama", tmdbId = "1").recommending("20,10"),
                series("Ten", tmdbId = "10"),
                series("Twenty", tmdbId = "20"),
                series("Just drama", genre = "Drama"),
            ),
        )

        val similar = catalog.moreLikeThis(storedShow("Harbour"))

        assertThat(similar.map { it.name }).containsExactly("Twenty", "Ten", "Just drama").inOrder()
    }

    @Test
    fun `without a TMDB Key, or for a film with no TMDB id, genres decide, as before TMDB was asked`() = runTest {
        db.movies().upsertAll(
            listOf(
                movie("Keyless", genre = "Drama", tmdbId = "1").recommending("10"),
                movie("No id", genre = "Drama").recommending("10"),
                movie("Ten", tmdbId = "10"),
                movie("Long Night", genre = "Drama"),
            ),
        )

        val keyless = test.catalog.moreLikeThis(stored("Keyless"))
        val catalog = test.catalogWithTmdb()
        val noId = catalog.moreLikeThis(stored("No id"))

        assertThat(keyless.map { it.name }).containsExactly("No id", "Long Night")
        assertThat(noId.map { it.name }).containsExactly("Keyless", "Long Night")
    }
}
