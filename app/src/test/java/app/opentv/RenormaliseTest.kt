/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import app.opentv.data.model.Category
import app.opentv.data.model.Channel
import app.opentv.data.model.Source
import app.opentv.data.model.SourceKind
import app.opentv.data.model.StreamKind
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

// Plain Application: OpenTvApp schedules WorkManager on start, which the database doesn't need.
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class RenormaliseTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val catalogue = TestCatalog(context)
    private val db = catalogue.db

    @After
    fun close() = catalogue.close()

    private suspend fun provider(id: Long, kind: SourceKind = SourceKind.XTREAM) =
        db.sources().insert(Source(id = id, name = "Provider $id", kind = kind, url = "http://example.com/$id"))

    private fun channel(sourceId: Long, streamId: String, name: String, categoryId: String) = Channel(
        sourceId = sourceId,
        streamId = streamId,
        name = name,
        categoryId = categoryId,
        logoUrl = null,
        epgChannelId = null,
        number = null,
        streamUrl = "http://example.com/live/u/p/$sourceId-$streamId.ts",
    )

    private suspend fun category(sourceId: Long, id: String, name: String) =
        db.categories().upsertAll(listOf(Category(id = id, sourceId = sourceId, name = name, kind = StreamKind.LIVE)))

    @Test
    fun `two providers with the same category id each label their channels from their own category`() = runTest {
        provider(1)
        provider(2)
        category(sourceId = 1, id = "7", name = "UK| GENERAL HD")
        category(sourceId = 2, id = "7", name = "UK| GENERAL 4K")
        db.channels().replaceCatalogue(1, listOf(channel(1, "a", "BBC One", categoryId = "7")), syncStamp = 1)
        db.channels().replaceCatalogue(2, listOf(channel(2, "b", "BBC One", categoryId = "7")), syncStamp = 1)

        catalogue.catalog.renormalizeAll()

        val labelOf = db.channels().everyRow().associate { it.sourceId to it.qualityLabel }
        assertThat(labelOf).containsExactly(1L, "HD", 2L, "4K")
    }

    @Test
    fun `what a refresh or another device changes while names are being cleaned stays changed`() = runTest {
        provider(1)
        category(sourceId = 1, id = "7", name = "UK| GENERAL")
        db.channels().replaceCatalogue(
            1,
            listOf(channel(1, "a", "BBC One HD", categoryId = "7"), channel(1, "b", "ITV1 HD", categoryId = "7")),
            syncStamp = 1,
        )
        val itv = db.channels().everyRow().single { it.streamId == "b" }
        db.channels().setHidden(itv.id, hidden = true)

        catalogue.catalog.renormalizeAll(
            beforeWrite = {
                // A refresh drops BBC One, and another device shows ITV1 again.
                db.openHelper.writableDatabase.execSQL("DELETE FROM channels WHERE streamId = 'a'")
                db.channels().setHidden(itv.id, hidden = false)
            },
        )

        val rows = db.channels().everyRow()
        assertThat(rows.map { it.streamId }).containsExactly("b")
        assertThat(rows.single().hidden).isFalse()
        assertThat(rows.single().displayName).isEqualTo("ITV1")
    }

    @Test
    fun `a playlist with more group-titles than SQLite can take in one query still gets cleaned`() = runTest {
        // Past the 32,766 bound variables Robolectric's SQLite allows, and far past the Stick's 999.
        val groups = 33_000
        provider(1, SourceKind.M3U)
        db.categories().upsertAll(
            (0 until groups).map { Category(id = "g$it", sourceId = 1, name = "Group $it HD", kind = StreamKind.LIVE) },
        )
        db.channels().replaceCatalogue(
            1,
            (0 until groups).map { channel(1, "s$it", "Channel $it", categoryId = "g$it") },
            syncStamp = 1,
        )

        catalogue.catalog.renormalizeAll()

        assertThat(db.channels().everyRow().map { it.qualityLabel }.distinct()).containsExactly("HD")
    }
}
