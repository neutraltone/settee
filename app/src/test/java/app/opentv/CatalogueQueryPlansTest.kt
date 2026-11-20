/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv

import androidx.sqlite.db.SimpleSQLiteQuery
import androidx.test.core.app.ApplicationProvider
import app.opentv.data.db.ALL_MOVIES
import app.opentv.data.db.ALL_SERIES
import app.opentv.data.db.CHANNELS_NAMED
import app.opentv.data.db.EPISODES_OF_PLAYED_SERIES
import app.opentv.data.db.MOVIES_IN_CATEGORY
import app.opentv.data.db.NEWEST_MOVIES_NOT_LOOKED_UP
import app.opentv.data.db.NEWEST_SERIES_TMDB_DUE
import app.opentv.data.db.PLAYED_EPISODES
import app.opentv.data.db.SERIES_IN_CATEGORY
import app.opentv.data.db.STARTED_SERIES
import com.google.common.truth.Truth.assertThat
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * How SQLite answers the film and series queries that sort by `addedMillis`, which only Recently Added
 * should answer from that column's index (see `RecentRowsTest`).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class CatalogueQueryPlansTest {

    private val test = TestCatalog(ApplicationProvider.getApplicationContext())

    @After
    fun close() = test.close()

    /** SQLite's plan for [sql], each of its `:named` arguments bound to 1. */
    private fun planOf(sql: String): List<String> {
        val arguments = Regex(":\\w+").findAll(sql).count()
        val explain = SimpleSQLiteQuery(
            "EXPLAIN QUERY PLAN " + sql.replace(Regex(":\\w+"), "?"),
            Array<Any>(arguments) { 1 },
        )
        return test.db.query(explain).use { rows -> buildList { while (rows.moveToNext()) add(rows.getString(3)) } }
    }

    @Test
    fun `reading every title goes straight through the table rather than through the added index`() {
        for (sql in listOf(ALL_MOVIES, ALL_SERIES, MOVIES_IN_CATEGORY, SERIES_IN_CATEGORY)) {
            assertThat(planOf(sql).filter { "addedMillis" in it }).isEmpty()
        }
    }

    @Test
    fun `the background lookups find a source's titles by source rather than reading every source's`() {
        for (sql in listOf(NEWEST_MOVIES_NOT_LOOKED_UP, NEWEST_SERIES_TMDB_DUE)) {
            assertThat(planOf(sql).first()).contains("(sourceId=?")
        }
    }

    @Test
    fun `what a Profile has played finds its episodes by id rather than reading every episode`() {
        // Joined on a key built from each episode's id, no index could serve it: every stored
        // episode was read whenever a position, episode or dismissal changed (#582).
        val scan = Regex("""SCAN (TABLE )?(episodes|played)\b""")
        for (sql in listOf(PLAYED_EPISODES, STARTED_SERIES, EPISODES_OF_PLAYED_SERIES)) {
            assertThat(planOf(sql).filter { scan.containsMatchIn(it) }).isEmpty()
        }
    }

    @Test
    fun `a Channel's rows in every region are found through an index rather than reading every channel`() {
        // Once a suffix match on the group key: a scan of every channel for each favourite (#582).
        assertThat(planOf(CHANNELS_NAMED).joinToString()).contains("USING INDEX")
        assertThat(planOf(CHANNELS_NAMED).filter { it.startsWith("SCAN") }).isEmpty()
    }
}
