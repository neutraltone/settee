/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv

import androidx.room.testing.MigrationTestHelper
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
    fun `the latest exported schema is the version the app opens`() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val db = OpenTvDatabase.build(context)
        try {
            assertThat(db.openHelper.readableDatabase.version).isEqualTo(exportedVersions.last())
        } finally {
            db.close()
            context.deleteDatabase("opentv.db")
        }
    }
}
