/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.room.testing.MigrationTestHelper
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import app.opentv.data.db.OpenTvDatabase
import app.opentv.data.db.wontOpen
import app.opentv.data.model.Favourite
import app.opentv.data.model.StreamKind
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertThrows
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Which databases Settee may wipe (#334). Only strangers: schemas 1 to 8, which v0.2.0 to v0.4.0
 * shipped before migrations were kept, and upstream OpenTV's 11, which a device that ran a build from
 * before the schema restarted still holds. Everything from 9 on migrates, so a missing migration from
 * one of those fails rather than silently costing a viewer their Favourites.
 */
// Plain Application: OpenTvApp schedules WorkManager on start, which the database doesn't need.
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class OpenTvDatabaseTest {

    @get:Rule
    val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        OpenTvDatabase::class.java,
    )

    private val context: Context = ApplicationProvider.getApplicationContext()
    private var db: OpenTvDatabase? = null
    private var wipes = 0

    private fun open(): OpenTvDatabase = OpenTvDatabase.build(context, onWiped = { wipes++ }).also { db = it }

    private fun OpenTvDatabase.version(): Int = openHelper.readableDatabase.version

    /** A database Settee holds no schema for, at [version], with one provider in it. */
    private fun leaveStrangerDatabase(version: Int) {
        val path = context.getDatabasePath(OpenTvDatabase.FILE_NAME).apply { parentFile?.mkdirs() }
        SQLiteDatabase.openOrCreateDatabase(path, null).use {
            it.execSQL("CREATE TABLE sources (id INTEGER PRIMARY KEY, name TEXT, enabled INTEGER)")
            it.execSQL("INSERT INTO sources VALUES (1, 'Old provider', 1)")
            it.version = version
        }
    }

    @Before
    fun startWithNoDatabase() {
        context.deleteDatabase(OpenTvDatabase.FILE_NAME)
    }

    @After
    fun closeAndDeleteDatabase() {
        db?.close()
        context.deleteDatabase(OpenTvDatabase.FILE_NAME)
    }

    @Test
    fun `a fresh install opens at the latest schema without counting as a wipe`() {
        assertThat(open().version()).isEqualTo(LATEST)
        assertThat(wipes).isEqualTo(0)
    }

    @Test
    fun `a database from v0_4_0 or older starts empty, and says so so settings holding row ids are reset`() = runTest {
        leaveStrangerDatabase(8)

        val opened = open()

        assertThat(opened.version()).isEqualTo(LATEST)
        assertThat(opened.sources().enabled()).isEmpty()
        assertThat(wipes).isEqualTo(1)
    }

    @Test
    fun `a database left by a pre-restart build starts empty, and says so`() = runTest {
        leaveStrangerDatabase(11)

        val opened = open()

        assertThat(opened.version()).isEqualTo(LATEST)
        assertThat(opened.sources().enabled()).isEmpty()
        assertThat(wipes).isEqualTo(1)
    }

    @Test
    fun `a Favourite starred under schema 9 is still there after the upgrade, with nothing wiped`() = runTest {
        // The schemas MigrationTestHelper builds from are packaged into debug assets only.
        assumeTrue(BuildConfig.DEBUG)
        helper.createDatabase(OpenTvDatabase.FILE_NAME, 9).use {
            it.execSQL(
                "INSERT INTO favourites (profileId, kind, source, itemId, changedAtMillis, removed) " +
                    "VALUES (1, 'MOVIE', 'Provider', '42', 1000, 0)",
            )
        }

        val favourites = open().favourites().all()

        assertThat(favourites).containsExactly(Favourite(1, StreamKind.MOVIE, "Provider", "42", 1000))
        assertThat(wipes).isEqualTo(0)
    }

    @Test
    fun `a database from a newer Settee is refused rather than wiped`() {
        leaveStrangerDatabase(NEWER)

        val refused = assertThrows(IllegalStateException::class.java) { open().version() }

        assertThat(refused).hasMessageThat().contains("A migration from $NEWER to $LATEST was required but not found")
        assertThat(wipes).isEqualTo(0)
    }

    @Test
    fun `a database that won't open says why, so the alarms and the first screen can stop short of crashing`() {
        leaveStrangerDatabase(NEWER)

        val failure = open().wontOpen()

        assertThat(failure).isInstanceOf(IllegalStateException::class.java)
        assertThat(wipes).isEqualTo(0)
    }

    @Test
    fun `a database that opens has nothing to say`() {
        assertThat(open().wontOpen()).isNull()
    }

    private companion object {
        /** OpenTvDatabase's version. A schema bump changes it here, and nothing else in this test. */
        const val LATEST = 15

        /** A database left by a newer Settee, after a downgrade. */
        const val NEWER = LATEST + 1
    }
}
