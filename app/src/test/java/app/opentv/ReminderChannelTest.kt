/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import app.opentv.data.db.OpenTvDatabase
import app.opentv.data.model.Channel
import app.opentv.data.model.GuideProgramme
import app.opentv.data.model.Source
import app.opentv.data.model.SourceKind
import app.opentv.data.repo.ReminderRepository
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** A reminder switches to its channel, even one a refresh left out for a while (#333). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class ReminderChannelTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    private val db = Room.inMemoryDatabaseBuilder(context, OpenTvDatabase::class.java)
        .allowMainThreadQueries()
        .build()

    private val channels = db.channels()
    private val reminders = ReminderRepository(db.reminders())

    @After
    fun close() = db.close()

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
        epgChannelId = "station.b",
        startUtcMillis = 2_000_000_000_000,
        endUtcMillis = 2_000_003_600_000,
        title = "Countryfile",
    )

    private suspend fun stored(sourceId: Long, streamId: String) =
        channels.forSource(sourceId).single { it.streamId == streamId }

    @Test
    fun `a reminder switches to its channel after the channel misses a refresh`() = runTest {
        val sourceId = db.sources().insert(
            Source(name = "Home", kind = SourceKind.M3U, url = "https://example.com/h.m3u"),
        )
        val a = fromProvider(sourceId, "a")
        val b = fromProvider(sourceId, "b")
        channels.replaceCatalogue(sourceId, listOf(a, b), syncStamp = 1)
        val id = reminders.remind(stored(sourceId, "b"), tomorrow, autoTune = false)!!

        channels.replaceCatalogue(sourceId, listOf(a), syncStamp = 2)
        channels.replaceCatalogue(sourceId, listOf(a, b), syncStamp = 3)

        assertThat(reminders.byId(id)!!.channelId).isEqualTo(stored(sourceId, "b").id)
    }

    @Test
    fun `a programme is reminded of once`() = runTest {
        val sourceId = db.sources().insert(
            Source(name = "Home", kind = SourceKind.M3U, url = "https://example.com/h.m3u"),
        )
        channels.replaceCatalogue(sourceId, listOf(fromProvider(sourceId, "b")), syncStamp = 1)
        val b = stored(sourceId, "b")

        reminders.remind(b, tomorrow, autoTune = false)

        assertThat(reminders.remind(b, tomorrow, autoTune = true)).isNull()
    }
}
