/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv

import androidx.test.core.app.ApplicationProvider
import app.opentv.data.model.Channel
import app.opentv.data.model.LiveStreamFormat
import app.opentv.data.model.Source
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.junit.After
import org.junit.Assert.assertThrows
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Writes that touch a whole provider or category at once land in one transaction, and only on the
 * rows that change (#263): switching a provider's live format, and the web manager's reorder.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class BulkChannelWritesTest {

    private val test = TestCatalog(ApplicationProvider.getApplicationContext())
    private val db = test.db

    @After
    fun close() = test.close()

    private fun channel(streamId: String, url: String) = Channel(
        sourceId = PANEL_SOURCE.id,
        streamId = streamId,
        name = "Channel $streamId",
        displayName = "Channel $streamId",
        groupKey = "ch:$streamId",
        qualityRank = 200,
        qualityLabel = "HD",
        categoryId = "1",
        logoUrl = null,
        epgChannelId = null,
        number = null,
        streamUrl = url,
    )

    /** [count] channels on [PANEL_SOURCE], each with the URL [PANEL_SOURCE]'s HLS format gives it. */
    private suspend fun hlsProvider(count: Int): Source {
        val source = PANEL_SOURCE.copy(liveFormat = LiveStreamFormat.HLS)
        db.sources().insert(source)
        db.channels().upsertAll((1..count).map { channel("$it", "http://example.com/live/u/p/$it.m3u8") })
        return source
    }

    @Test
    fun `switching a big provider's live format rewrites every channel in one go`() = runBlocking {
        hlsProvider(3_000)
        // Reads the channels over and over while the switch runs. Committed one row at a time, some
        // read would find both formats; in one transaction, every read finds all old or all new.
        // (Counting Room's invalidations would say the same, but Room delivers them asynchronously.)
        val formatsSeen = mutableListOf<Set<String>>()
        coroutineScope {
            val reading = launch(Dispatchers.IO) {
                while (isActive) {
                    formatsSeen += db.channels().everyRow().map { it.streamUrl.substringAfterLast('.') }.toSet()
                    yield()
                }
            }
            test.catalog.setLiveFormat(PANEL_SOURCE.id, LiveStreamFormat.MPEG_TS)
            reading.cancelAndJoin()
        }

        assertThat(db.sources().byId(PANEL_SOURCE.id)?.liveFormat).isEqualTo(LiveStreamFormat.MPEG_TS)
        assertThat(db.channels().everyRow().map { it.streamUrl }.filterNot { it.endsWith(".ts") }).isEmpty()
        assertThat(formatsSeen.filter { it.size > 1 }).isEmpty()
    }

    @Test
    fun `a format switch that fails partway leaves the provider and its channels on the old format`() = runBlocking {
        val source = hlsProvider(20)
        var built = 0

        assertThrows(IllegalStateException::class.java) {
            runBlocking {
                db.channels().switchLiveFormat(source.id, LiveStreamFormat.MPEG_TS) { _, streamId ->
                    check(++built < 10) { "stopped partway" }
                    "http://example.com/live/u/p/$streamId.ts"
                }
            }
        }

        assertThat(db.sources().byId(PANEL_SOURCE.id)?.liveFormat).isEqualTo(LiveStreamFormat.HLS)
        assertThat(db.channels().everyRow().map { it.streamUrl }.filterNot { it.endsWith(".m3u8") }).isEmpty()
    }

    @Test
    fun `a reorder saves the order and writes only the channels whose place changed`() = runBlocking {
        hlsProvider(5)
        val ids = db.channels().everyRow().sortedBy { it.streamId }.map { it.id }
        val (a, b, c, d, e) = ids

        test.catalog.setChannelOrder(listOf(a, b, c, d, e))
        val unchanged = test.catalog.setChannelOrder(listOf(a, b, c, d, e))
        val oneMove = test.catalog.setChannelOrder(listOf(a, c, b, d, e))

        assertThat(unchanged).isEqualTo(0)
        assertThat(oneMove).isEqualTo(2)
        assertThat(db.channels().everyRow().sortedBy { it.sortIndex }.map { it.id })
            .containsExactly(a, c, b, d, e)
            .inOrder()
    }
}
