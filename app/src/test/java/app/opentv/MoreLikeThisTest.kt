/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv

import androidx.test.core.app.ApplicationProvider
import app.opentv.data.model.Movie
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The films a film's page offers under More like this. */
// Plain Application: OpenTvApp schedules WorkManager on start, which the database doesn't need.
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class MoreLikeThisTest {

    private val test = TestCatalog(ApplicationProvider.getApplicationContext())
    private val db = test.db

    @After
    fun close() = test.close()

    private fun movie(
        name: String,
        genre: String? = null,
        rating: Double? = null,
        sourceId: Long = 1,
        categoryId: String? = null,
    ) = Movie(
        sourceId = sourceId,
        streamId = name,
        name = name,
        categoryId = categoryId,
        posterUrl = null,
        rating = rating,
        year = null,
        plot = null,
        durationSeconds = null,
        containerExtension = "mp4",
        streamUrl = "",
        genre = genre,
    )

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
                movie("Untitled", categoryId = "7"),
                movie("Same shelf", categoryId = "7", rating = 6.0),
                movie("Same shelf, better", categoryId = "7", rating = 8.0),
                movie("Other shelf", categoryId = "9", rating = 9.0),
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
}
