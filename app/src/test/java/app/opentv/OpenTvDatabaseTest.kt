/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.test.core.app.ApplicationProvider
import app.opentv.data.db.OpenTvDatabase
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Settee restarted its schema at version 1, but any device that ran a build from before the
 * restart still has an upstream database at version 11. Room refuses to open a database newer
 * than its schema unless told otherwise, so without a destructive fallback those installs crash
 * on launch instead of starting clean. When the fallback goes at the first tagged release, the
 * wipe tests go with it.
 */
// Plain Application: OpenTvApp schedules WorkManager on start, which the database doesn't need.
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class OpenTvDatabaseTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private var db: OpenTvDatabase? = null
    private var wipes = 0

    private fun open(): OpenTvDatabase = OpenTvDatabase.build(context, onWiped = { wipes++ }).also { db = it }

    private fun OpenTvDatabase.version(): Int = openHelper.readableDatabase.version

    private fun leaveUpstreamV11Database() {
        val path = context.getDatabasePath(OpenTvDatabase.FILE_NAME).apply { parentFile?.mkdirs() }
        SQLiteDatabase.openOrCreateDatabase(path, null).use {
            it.execSQL("CREATE TABLE sources (id INTEGER PRIMARY KEY, name TEXT, enabled INTEGER)")
            it.execSQL("INSERT INTO sources VALUES (1, 'Old provider', 1)")
            it.version = 11
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
    fun `a fresh install opens at schema version 1 without counting as a wipe`() {
        assertThat(open().version()).isEqualTo(1)
        assertThat(wipes).isEqualTo(0)
    }

    @Test
    fun `a database left by a pre-restart build is wiped rather than crashing`() = runTest {
        leaveUpstreamV11Database()

        val opened = open()

        assertThat(opened.version()).isEqualTo(1)
        assertThat(opened.sources().enabled()).isEmpty()
    }

    @Test
    fun `wiping the database is reported so settings holding row ids can be reset`() {
        leaveUpstreamV11Database()

        open().version()

        assertThat(wipes).isEqualTo(1)
    }
}
