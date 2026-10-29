/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import app.opentv.data.db.OpenTvDatabase
import app.opentv.data.model.Movie
import app.opentv.data.model.Series
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The order of Home's Recently Released and Recently Added rows, and what they leave out. */
// Plain Application: OpenTvApp schedules WorkManager on start, which the database doesn't need.
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class RecentRowsTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val db = Room.inMemoryDatabaseBuilder(context, OpenTvDatabase::class.java)
        .allowMainThreadQueries()
        .build()

    @After
    fun close() = db.close()

    private fun movie(name: String, releaseDate: Long? = null, added: Long = 0) = Movie(
        sourceId = 1,
        streamId = name,
        name = name,
        categoryId = null,
        posterUrl = null,
        rating = null,
        year = null,
        plot = null,
        durationSeconds = null,
        containerExtension = "mp4",
        streamUrl = "",
        addedMillis = added,
        releaseDate = releaseDate,
    )

    private fun series(name: String, latestAirDate: Long? = null, added: Long = 0) = Series(
        sourceId = 1,
        seriesId = name,
        name = name,
        categoryId = null,
        posterUrl = null,
        rating = null,
        year = null,
        plot = null,
        addedMillis = added,
        latestAirDate = latestAirDate,
    )

    @Test
    fun `recently released movies are newest first, leaving out future and undated ones`() = runTest {
        db.movies().upsertAll(
            listOf(
                movie("Older", releaseDate = 100),
                movie("Undated"),
                movie("Newer", releaseDate = 300),
                movie("Not out yet", releaseDate = 2_000),
                movie("Out today", releaseDate = 1_000),
            ),
        )

        val row = db.movies().observeRecentlyReleased(nowMillis = 1_000, limit = 30).first()

        assertThat(row.map { it.name }).containsExactly("Out today", "Newer", "Older").inOrder()
    }

    @Test
    fun `recently released rows stop at the cap`() = runTest {
        db.movies().upsertAll((1..5).map { movie("M$it", releaseDate = it.toLong()) })
        db.series().upsertAll((1..5).map { series("S$it", latestAirDate = it.toLong()) })

        assertThat(db.movies().observeRecentlyReleased(nowMillis = 1_000, limit = 2).first().map { it.name })
            .containsExactly("M5", "M4").inOrder()
        assertThat(db.series().observeRecentlyReleased(nowMillis = 1_000, limit = 2).first().map { it.name })
            .containsExactly("S5", "S4").inOrder()
    }

    @Test
    fun `recently released shows go by their newest episode, not when they started`() = runTest {
        db.series().upsertAll(
            listOf(
                series("Long runner, new episode yesterday", latestAirDate = 900, added = 1),
                series("New show, nothing since", latestAirDate = 500, added = 2),
                series("Never looked up", added = 3),
            ),
        )

        val row = db.series().observeRecentlyReleased(nowMillis = 1_000, limit = 30).first()

        assertThat(row.map { it.name })
            .containsExactly("Long runner, new episode yesterday", "New show, nothing since")
            .inOrder()
    }

    @Test
    fun `recently added rows go by when the provider added or last updated the title`() = runTest {
        db.movies().upsertAll(
            listOf(movie("Added first", added = 1, releaseDate = 900), movie("Added last", added = 2)),
        )
        db.series().upsertAll(listOf(series("Updated last", added = 2), series("Updated first", added = 1)))

        assertThat(db.movies().observeRecentlyAdded(30).first().map { it.name })
            .containsExactly("Added last", "Added first").inOrder()
        assertThat(db.series().observeRecentlyAdded(30).first().map { it.name })
            .containsExactly("Updated last", "Updated first").inOrder()
    }
}
