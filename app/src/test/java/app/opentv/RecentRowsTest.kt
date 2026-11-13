/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv

import androidx.sqlite.db.SimpleSQLiteQuery
import androidx.test.core.app.ApplicationProvider
import app.opentv.data.db.RECENT_MOVIES
import app.opentv.data.db.RECENT_SERIES
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

/** The order of Home's Recently Added row, and what it leaves out. */
// Plain Application: OpenTvApp schedules WorkManager on start, which the database doesn't need.
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class RecentRowsTest {

    private val test = TestCatalog(ApplicationProvider.getApplicationContext())
    private val db = test.db

    @After
    fun close() = test.close()

    private fun movie(name: String, added: Long = 0) = Movie(
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
    )

    private fun series(name: String, added: Long = 0) = Series(
        sourceId = 1,
        seriesId = name,
        name = name,
        categoryId = null,
        posterUrl = null,
        rating = null,
        year = null,
        plot = null,
        addedMillis = added,
    )

    @Test
    fun `Recently Added reads the newest titles from an index rather than sorting the whole library`() {
        val queries = listOf(RECENT_MOVIES to "index_movies_addedMillis", RECENT_SERIES to "index_series_addedMillis")
        for ((sql, index) in queries) {
            val explain = SimpleSQLiteQuery("EXPLAIN QUERY PLAN " + sql.replace(":limit", "?"), arrayOf<Any>(30))
            val plan = db.query(explain).use { rows -> buildList { while (rows.moveToNext()) add(rows.getString(3)) } }

            assertThat(plan.joinToString("\n")).contains(index)
            assertThat(plan.filter { "TEMP B-TREE" in it }).isEmpty()
        }
    }

    @Test
    fun `recently added rows go by when the provider added or last updated the title`() = runTest {
        db.movies().upsertAll(
            listOf(movie("Added first", added = 1), movie("Added last", added = 2)),
        )
        db.series().upsertAll(listOf(series("Updated last", added = 2), series("Updated first", added = 1)))

        assertThat(db.movies().observeRecentlyAdded(30).first().map { it.name })
            .containsExactly("Added last", "Added first").inOrder()
        assertThat(db.series().observeRecentlyAdded(30).first().map { it.name })
            .containsExactly("Updated last", "Updated first").inOrder()
    }

    @Test
    fun `Recently Added mixes movies and shows, the latest the provider added first`() = runTest {
        db.movies().upsertAll(listOf(movie("Heat", added = 1), movie("Alien", added = 3)))
        db.series().upsertAll(listOf(series("The Wire", added = 2), series("Taskmaster", added = 4)))

        val row = test.catalog.recentlyAdded().first()

        assertThat(row.map { it.name }).containsExactly("Taskmaster", "Alien", "The Wire", "Heat").inOrder()
    }

    @Test
    fun `Recently Added stops at 20 titles`() = runTest {
        db.movies().upsertAll((1..15).map { movie("M$it", added = it * 2L) })
        db.series().upsertAll((1..15).map { series("S$it", added = it * 2L + 1) })

        val row = test.catalog.recentlyAdded().first()

        assertThat(row).hasSize(20)
        assertThat(row.first().name).isEqualTo("S15")
    }

    @Test
    fun `Recently Added leaves out a kind that's switched off`() = runTest {
        db.movies().upsertAll(listOf(movie("Heat", added = 1)))
        db.series().upsertAll(listOf(series("The Wire", added = 2)))

        assertThat(test.catalog.recentlyAdded(shows = false).first().map { it.name }).containsExactly("Heat")
        assertThat(test.catalog.recentlyAdded(movies = false).first().map { it.name }).containsExactly("The Wire")
    }

    @Test
    fun `Recently Added leaves out titles the provider never dated, and breaks ties by name`() = runTest {
        db.movies().upsertAll(listOf(movie("Undated"), movie("Zodiac", added = 5), movie("Alien", added = 5)))
        db.series().upsertAll(listOf(series("Also undated"), series("Monk", added = 5)))

        val row = test.catalog.recentlyAdded().first()

        assertThat(row.map { it.name }).containsExactly("Alien", "Monk", "Zodiac").inOrder()
    }
}
