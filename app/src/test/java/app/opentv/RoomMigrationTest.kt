/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv

import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.test.platform.app.InstrumentationRegistry
import app.opentv.data.db.OpenTvDatabase
import com.google.common.truth.Truth.assertThat
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Walks every exported schema forward to the current one with the real migrations, so a schema
 * change that ships without a version bump and a matching Migration fails here rather than
 * falling through to the destructive rebuild and silently costing users their favourites.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class RoomMigrationTest {

    @get:Rule
    val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        OpenTvDatabase::class.java,
    )

    private val exportedVersions: List<Int> =
        InstrumentationRegistry.getInstrumentation().context.assets
            .list(OpenTvDatabase::class.java.canonicalName!!)
            .orEmpty()
            .mapNotNull { it.removeSuffix(".json").toIntOrNull() }
            .sorted()

    // The schemas are packaged into debug assets only; the debug run covers this.
    @Before
    fun debugOnly() {
        assumeTrue(BuildConfig.DEBUG)
    }

    @Test
    fun `every exported schema migrates to the latest one`() {
        assertThat(exportedVersions).isNotEmpty()
        val latest = exportedVersions.last()

        for (from in exportedVersions.dropLast(1)) {
            val name = "migration-from-$from.db"
            helper.createDatabase(name, from).close()
            helper.runMigrationsAndValidate(name, latest, true, *OpenTvDatabase.MIGRATIONS).close()
        }
    }

    @Test
    fun `films TMDB answered about before 12 are dated then, so they aren't all asked again at once`() {
        val name = "tmdb-dates.db"
        helper.createDatabase(name, 10).use { db ->
            db.execSQL(
                "INSERT INTO movies (id, sourceId, streamId, name, streamUrl, addedMillis, tmdbLookedUp) VALUES " +
                    "(1, 1, 'asked', 'Asked', 'http://example.com/1.mp4', 0, 1), " +
                    "(2, 1, 'unasked', 'Unasked', 'http://example.com/2.mp4', 0, 0)",
            )
            db.execSQL("INSERT INTO series (id, sourceId, seriesId, name, addedMillis) VALUES (1, 1, 's', 'Show', 0)")
        }
        val before = System.currentTimeMillis()

        val db = helper.runMigrationsAndValidate(name, 12, true, *OpenTvDatabase.MIGRATIONS)
        val after = System.currentTimeMillis()

        db.use {
            fun answeredAt(table: String, id: Int): Long? =
                it.query("SELECT tmdbAnsweredAt FROM $table WHERE id = $id").use { row ->
                    row.moveToFirst()
                    if (row.isNull(0)) null else row.getLong(0)
                }
            // SQLite's clock is in whole seconds.
            assertThat(answeredAt("movies", 1)).isIn(com.google.common.collect.Range.closed(before - 1000, after))
            assertThat(answeredAt("movies", 2)).isNull()
            assertThat(answeredAt("series", 1)).isNull()
        }
    }

    @Test
    fun `rebuilding the film and series tables for 12 keeps every row, its id and its credits`() {
        val name = "rebuilt.db"
        helper.createDatabase(name, 10).use { db ->
            db.execSQL(
                "INSERT INTO movies (id, sourceId, streamId, name, streamUrl, addedMillis, plot) VALUES " +
                    "(7, 1, 'film', 'Film', 'http://example.com/7.mp4', 5, 'A film.')",
            )
            db.execSQL(
                "INSERT INTO series (id, sourceId, seriesId, name, addedMillis, plot, latestAirDate) VALUES " +
                    "(9, 1, 'show', 'Show', 5, 'A show.', 123)",
            )
            db.execSQL("INSERT INTO movie_credits (movieId, role, nameKey) VALUES (7, 'cast', 'someone')")
            db.execSQL("INSERT INTO series_credits (seriesId, nameKey) VALUES (9, 'someone')")
        }

        helper.runMigrationsAndValidate(name, 12, true, *OpenTvDatabase.MIGRATIONS).use { db ->
            assertThat(db.first("SELECT plot FROM movies WHERE id = 7")).isEqualTo("A film.")
            assertThat(db.first("SELECT plot FROM series WHERE id = 9")).isEqualTo("A show.")
            assertThat(db.first("SELECT nameKey FROM movie_credits WHERE movieId = 7")).isEqualTo("someone")
            assertThat(db.first("SELECT nameKey FROM series_credits WHERE seriesId = 9")).isEqualTo("someone")
        }
    }

    @Test
    fun `indexing when titles were added for 13 keeps every film and show`() {
        val name = "added-index.db"
        helper.createDatabase(name, 12).use { db ->
            db.execSQL(
                "INSERT INTO movies (id, sourceId, streamId, name, streamUrl, addedMillis) VALUES " +
                    "(7, 1, 'film', 'Film', 'http://example.com/7.mp4', 5)",
            )
            db.execSQL(
                "INSERT INTO series (id, sourceId, seriesId, name, addedMillis) VALUES (9, 1, 'show', 'Show', 6)",
            )
        }

        helper.runMigrationsAndValidate(name, 13, true, *OpenTvDatabase.MIGRATIONS).use { db ->
            assertThat(db.first("SELECT name || ':' || addedMillis FROM movies WHERE id = 7")).isEqualTo("Film:5")
            assertThat(db.first("SELECT name || ':' || addedMillis FROM series WHERE id = 9")).isEqualTo("Show:6")
        }
    }

    @Test
    fun `a series link set before 15 records its channel's source and stream id`() {
        val name = "channel-identity.db"
        helper.createDatabase(name, 14).use { db ->
            db.execSQL(
                "INSERT INTO sources (id, name, kind, url, userAgent, liveFormat, enabled, lastCatalogSyncMillis) " +
                    "VALUES (1, 'Home', 'M3U', 'https://example.com/h.m3u', 'ua', 'TS', 1, 0)",
            )
            db.execSQL(
                "INSERT INTO channels (id, sourceId, streamId, name, displayName, groupKey, qualityRank, " +
                    "qualityLabel, streamUrl, hidden, sortIndex, lastSeenMillis) " +
                    "VALUES (5, 1, 'itv', 'ITV', 'ITV', 'itv', 0, '', 'https://example.com/itv.ts', 0, 0, 0)",
            )
            db.execSQL(
                "INSERT INTO series_rules (id, channelId, channelName, titleKey, title, createdAtMillis, enabled) " +
                    "VALUES (1, 5, 'ITV', 'countryfile', 'Countryfile', 0, 1), (2, 99, 'Gone', 'news', 'News', 0, 1)",
            )
        }

        helper.runMigrationsAndValidate(name, 15, true, *OpenTvDatabase.MIGRATIONS).use { db ->
            fun channelOf(rule: Int) = db.first("SELECT source || ':' || streamId FROM series_rules WHERE id = $rule")
            assertThat(channelOf(1)).isEqualTo("Home:itv")
            assertThat(channelOf(2)).isEqualTo(":")
        }
    }

    /** The first column of [sql]'s first row. */
    private fun SupportSQLiteDatabase.first(sql: String): String = query(sql).use { row ->
        row.moveToFirst()
        row.getString(0)
    }

    @Test
    fun `the latest exported schema is the version the app opens`() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val db = OpenTvDatabase.build(context)
        try {
            assertThat(db.openHelper.readableDatabase.version).isEqualTo(exportedVersions.last())
        } finally {
            db.close()
            context.deleteDatabase(OpenTvDatabase.FILE_NAME)
        }
    }
}
