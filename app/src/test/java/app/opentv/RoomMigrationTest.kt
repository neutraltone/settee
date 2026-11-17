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
import app.opentv.data.repo.tmdbDue
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

    @Test
    fun `stamping films and series for 16 keeps every film, show and resume point`() {
        val name = "titles-last-seen.db"
        helper.createDatabase(name, 15).use { db ->
            db.execSQL(
                "INSERT INTO movies (id, sourceId, streamId, name, streamUrl, addedMillis) VALUES " +
                    "(7, 1, 'film', 'Film', 'http://example.com/7.mp4', 5)",
            )
            db.execSQL(
                "INSERT INTO series (id, sourceId, seriesId, name, addedMillis) VALUES (9, 1, 'show', 'Show', 6)",
            )
            db.execSQL(
                "INSERT INTO playback_positions (profileId, mediaKey, positionMillis, durationMillis, " +
                    "updatedAtMillis, mark) VALUES (1, 'movie:7', 600000, 6000000, 50, 'WATCHED')",
            )
        }

        helper.runMigrationsAndValidate(name, 16, true, *OpenTvDatabase.MIGRATIONS).use { db ->
            assertThat(db.first("SELECT name || ':' || lastSeenMillis FROM movies WHERE id = 7")).isEqualTo("Film:0")
            assertThat(db.first("SELECT name || ':' || lastSeenMillis FROM series WHERE id = 9")).isEqualTo("Show:0")
            val position = "SELECT positionMillis || ':' || mark FROM playback_positions WHERE mediaKey = 'movie:7'"
            assertThat(db.first(position)).isEqualTo("600000:WATCHED")
        }
    }

    @Test
    fun `films TMDB answered without their poster being saved are due again after 17, and nothing else changes`() {
        val name = "poster-reask.db"
        val now = System.currentTimeMillis()
        val lastWeek = now - 7 * 24 * 60 * 60 * 1000L
        val columns = "id, sourceId, streamId, name, posterUrl, plot, director, streamUrl, addedMillis, " +
            "detailsLookedUp, tmdbAnsweredAt"
        helper.createDatabase(name, 16).use { db ->
            db.execSQL(
                "INSERT INTO movies ($columns) VALUES " +
                    "(1, 1, 'blank', 'Blank', NULL, 'One.', 'TMDB', 'http://example.com/1.mp4', 5, 1, $lastWeek), " +
                    "(2, 1, 'poster', 'Poster', 'http://example.com/2.jpg', 'Two.', 'TMDB', " +
                    "'http://example.com/2.mp4', 6, 1, $lastWeek), " +
                    "(3, 1, 'unasked', 'Unasked', NULL, 'Three.', NULL, 'http://example.com/3.mp4', 7, 0, NULL)",
            )
            db.execSQL(
                "INSERT INTO favourites (profileId, kind, source, itemId, changedAtMillis, removed) " +
                    "VALUES (1, 'MOVIE', 'Home', 'blank', 9, 0)",
            )
        }

        helper.runMigrationsAndValidate(name, 17, true, *OpenTvDatabase.MIGRATIONS).use { db ->
            fun film(id: Int) = db.first(
                "SELECT id || '|' || sourceId || '|' || streamId || '|' || name || '|' || " +
                    "IFNULL(posterUrl, '-') || '|' || plot || '|' || IFNULL(director, '-') || '|' || " +
                    "streamUrl || '|' || addedMillis || '|' || detailsLookedUp FROM movies WHERE id = $id",
            )
            fun answeredAt(id: Int): Long? = db.query("SELECT tmdbAnsweredAt FROM movies WHERE id = $id").use {
                it.moveToFirst()
                if (it.isNull(0)) null else it.getLong(0)
            }
            assertThat(film(1)).isEqualTo("1|1|blank|Blank|-|One.|TMDB|http://example.com/1.mp4|5|1")
            assertThat(film(2))
                .isEqualTo("2|1|poster|Poster|http://example.com/2.jpg|Two.|TMDB|http://example.com/2.mp4|6|1")
            assertThat(film(3)).isEqualTo("3|1|unasked|Unasked|-|Three.|-|http://example.com/3.mp4|7|0")
            // Due for TMDB again, but still marked answered, so a list refresh keeps TMDB's director.
            assertThat(answeredAt(1)).isNotNull()
            assertThat(tmdbDue(answeredAt(1), now)).isTrue()
            assertThat(answeredAt(2)).isEqualTo(lastWeek)
            assertThat(answeredAt(3)).isNull()
            assertThat(db.first("SELECT source || ':' || itemId FROM favourites WHERE profileId = 1"))
                .isEqualTo("Home:blank")
        }
    }

    @Test
    fun `20 adds an empty table for episode timings and keeps every Favourite and resume point`() {
        val name = "episode-timings.db"
        helper.createDatabase(name, 19).use { db ->
            db.execSQL(
                "INSERT INTO playback_positions (profileId, mediaKey, positionMillis, durationMillis, " +
                    "updatedAtMillis, mark) VALUES (1, 'ep:7', 600000, 2400000, 50, NULL)",
            )
        }

        helper.runMigrationsAndValidate(name, 20, true, *OpenTvDatabase.MIGRATIONS).use { db ->
            assertThat(db.first("SELECT COUNT(*) FROM episode_timings")).isEqualTo("0")
            db.execSQL(
                "INSERT INTO episode_timings (show, season, episode, creditsStartMillis, sceneAfterCredits, " +
                    "noIntro, noRecap, noCredits, source, fetchedAtMillis) " +
                    "VALUES ('tmdb:1', 1, 1, 3432884, 0, 0, 0, 0, 'theintrodb', 5)",
            )
            assertThat(db.first("SELECT creditsStartMillis FROM episode_timings WHERE show = 'tmdb:1'"))
                .isEqualTo("3432884")
            assertThat(db.first("SELECT positionMillis FROM playback_positions WHERE mediaKey = 'ep:7'"))
                .isEqualTo("600000")
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
