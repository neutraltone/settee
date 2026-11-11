/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv

import androidx.room.Room
import androidx.room.testing.MigrationTestHelper
import androidx.test.platform.app.InstrumentationRegistry
import app.opentv.data.db.OpenTvDatabase
import app.opentv.data.repo.CatalogueRefresh
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Titles saved before the app kept credits (database version 9) reach people's pages once the app
 * has started, without the viewer adding their providers again.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class PersonCreditsUpgradeTest {

    @get:Rule
    val helper = MigrationTestHelper(InstrumentationRegistry.getInstrumentation(), OpenTvDatabase::class.java)

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private var test: TestCatalog? = null

    @After
    fun close() {
        test?.close()
        context.deleteDatabase(NAME)
    }

    @Test
    fun `films and shows saved before credits were kept are on their people's pages after the app starts`() = runTest {
        helper.createDatabase(NAME, 9).apply {
            execSQL(
                "INSERT INTO movies (id, sourceId, streamId, name, streamUrl, addedMillis, `cast`, director) " +
                    "VALUES (1, 1, '3', 'Drive', 'http://example.com/3.mp4', 0, " +
                    "'Ryan Gosling', 'Nicolas Winding Refn')",
            )
            execSQL(
                "INSERT INTO series (id, sourceId, seriesId, name, addedMillis, `cast`) " +
                    "VALUES (1, 1, '9', 'Broadchurch', 0, 'Olivia Colman')",
            )
            close()
        }
        val test = TestCatalog(context, db = upgrade()).also { test = it }

        test.refresh().refresh(CatalogueRefresh.Trigger.AppLaunch)
        test.catalog.awaitBackground()

        assertThat(test.titlesWith("Nicolas Winding Refn")).containsExactly("Drive")
        assertThat(test.titlesWith("Olivia Colman")).containsExactly("Broadchurch")
    }

    @Test
    fun `films given a genre before cast was kept are looked up again for it`() = runTest {
        helper.createDatabase(NAME, 9).apply {
            execSQL(
                "INSERT INTO movies (id, sourceId, streamId, name, streamUrl, addedMillis, genre) " +
                    "VALUES (1, 1, '3', 'Drive', 'http://example.com/3.mp4', 0, 'Crime')",
            )
            close()
        }
        val test = TestCatalog(context, db = upgrade()).also { test = it }
        test.db.sources().insert(PANEL_SOURCE)
        test.panel.replies["get_vod_info"] = """{"info": {"genre": "Thriller", "cast": "Ryan Gosling"}}"""

        test.refresh().refresh(CatalogueRefresh.Trigger.AppLaunch)
        test.catalog.awaitBackground()

        assertThat(test.titlesWith("Ryan Gosling")).containsExactly("Drive")
        assertThat(test.db.movies().all().single().genre).isEqualTo("Crime")
    }

    private fun upgrade() = Room.databaseBuilder(context, OpenTvDatabase::class.java, NAME)
        .addMigrations(*OpenTvDatabase.MIGRATIONS)
        .allowMainThreadQueries()
        .build()

    private companion object {
        const val NAME = "before-credits.db"
    }
}
