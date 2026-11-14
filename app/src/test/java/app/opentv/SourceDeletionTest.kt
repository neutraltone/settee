/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import app.opentv.data.model.Channel
import app.opentv.data.model.GuideProgramme
import app.opentv.data.model.SeriesRule
import app.opentv.data.model.Source
import app.opentv.data.model.SourceKind
import app.opentv.data.repo.ReminderRepository
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Deleting a source takes what was set on its channels with it, so a source added later under the
 * same name starts afresh rather than reviving them by name (#333).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class SourceDeletionTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val test = TestCatalog(context)
    private val db = test.db
    private val channels = db.channels()
    private val reminders = ReminderRepository(db.reminders(), db.sources())

    @After
    fun close() = test.close()

    private suspend fun home() =
        db.sources().insert(Source(name = "Home", kind = SourceKind.M3U, url = "https://example.com/h.m3u"))

    private fun fromProvider(sourceId: Long, streamId: String) = Channel(
        sourceId = sourceId,
        streamId = streamId,
        name = "Station $streamId",
        groupKey = "station$streamId",
        streamUrl = "https://example.com/$streamId.ts",
        categoryId = null,
        logoUrl = null,
        epgChannelId = null,
        number = null,
    )

    private val tomorrow = GuideProgramme(
        epgChannelId = "b",
        startUtcMillis = 2_000_000_000_000,
        endUtcMillis = 2_000_003_600_000,
        title = "News",
    )

    @Test
    fun `a source added again under the same name has none of the deleted one's links, reminders or edits`() = runTest {
        val first = home()
        channels.replaceCatalogue(first, listOf(fromProvider(first, "a"), fromProvider(first, "b")), syncStamp = 1)
        val b = channels.forSource(first).single { it.streamId == "b" }
        channels.setCustomName(b.id, "My Station")
        db.seriesRules().insert(
            SeriesRule(
                channelId = b.id,
                source = "Home",
                streamId = "b",
                channelName = "B",
                titleKey = "news",
                title = "News",
            ),
        )
        reminders.remind(b, tomorrow, autoTune = false)
        channels.replaceCatalogue(first, listOf(fromProvider(first, "a")), syncStamp = 2)

        test.catalog.deleteSource(first)
        val again = home()
        channels.replaceCatalogue(again, listOf(fromProvider(again, "b")), syncStamp = 3)

        assertThat(channels.forSource(again).single().customName).isNull()
        assertThat(db.seriesRules().observeAll().first()).isEmpty()
        assertThat(reminders.observeAll().first()).isEmpty()
    }
}
