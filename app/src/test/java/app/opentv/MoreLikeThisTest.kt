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
}
