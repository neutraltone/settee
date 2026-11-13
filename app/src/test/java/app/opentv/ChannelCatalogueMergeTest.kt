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
import app.opentv.data.model.ChannelEpgMatch
import app.opentv.data.model.ChannelUserState
import app.opentv.data.model.Source
import app.opentv.data.model.SourceKind
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class ChannelCatalogueMergeTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    private val db = Room.inMemoryDatabaseBuilder(context, OpenTvDatabase::class.java)
        .allowMainThreadQueries()
        .build()

    private val channels = db.channels()

    @After
    fun close() = db.close()

    private suspend fun source(name: String) =
        db.sources().insert(Source(name = name, kind = SourceKind.M3U, url = "https://example.com/$name.m3u"))

    /** A row as a provider sends it on every refresh: fresh name and URL, none of the viewer's state. */
    private fun fromProvider(sourceId: Long, streamId: String, hidden: Boolean = false, refresh: Int = 1) = Channel(
        sourceId = sourceId,
        streamId = streamId,
        name = "Station $streamId ($refresh)",
        groupKey = "station$streamId",
        streamUrl = "https://example.com/$streamId/$refresh.ts",
        categoryId = null,
        logoUrl = null,
        epgChannelId = null,
        number = null,
        hidden = hidden,
    )

    private suspend fun stored(sourceId: Long, streamId: String) =
        channels.forSource(sourceId).single { it.streamId == streamId }

    @Test
    fun `a refresh keeps the viewer's settings and the row's id`() = runTest {
        val sourceId = source("Home")
        channels.replaceCatalogue(
            sourceId,
            listOf(fromProvider(sourceId, "a"), fromProvider(sourceId, "b")),
            syncStamp = 1,
        )
        val before = stored(sourceId, "a")
        channels.setHidden(before.id, hidden = true)
        channels.setSortIndex(before.id, 7)
        channels.setCustomName(before.id, "My Station")
        channels.setEpgOverride(before.id, "override.guide")
        channels.setMatchedEpgIds(listOf(ChannelEpgMatch(before.id, "matched.guide")))

        channels.replaceCatalogue(
            sourceId,
            listOf(fromProvider(sourceId, "a", refresh = 2), fromProvider(sourceId, "b", refresh = 2)),
            syncStamp = 2,
        )

        val after = stored(sourceId, "a")
        assertThat(after.id).isEqualTo(before.id)
        assertThat(after.hidden).isTrue()
        assertThat(after.sortIndex).isEqualTo(7)
        assertThat(after.customName).isEqualTo("My Station")
        assertThat(after.epgOverrideId).isEqualTo("override.guide")
        assertThat(after.matchedEpgId).isEqualTo("matched.guide")
        assertThat(after.name).isEqualTo("Station a (2)")
        assertThat(after.streamUrl).isEqualTo("https://example.com/a/2.ts")
        assertThat(after.lastSeenMillis).isEqualTo(2)
    }

    @Test
    fun `a row the provider now hides is hidden, even though the viewer never hid it`() = runTest {
        val sourceId = source("Home")
        channels.replaceCatalogue(sourceId, listOf(fromProvider(sourceId, "### UK ###")), syncStamp = 1)

        channels.replaceCatalogue(sourceId, listOf(fromProvider(sourceId, "### UK ###", hidden = true)), syncStamp = 2)

        assertThat(stored(sourceId, "### UK ###").hidden).isTrue()
    }

    @Test
    fun `the state a refresh keeps is read for that source's channels alone`() = runTest {
        val home = source("Home")
        val other = source("Other")
        channels.replaceCatalogue(home, listOf(fromProvider(home, "a")), syncStamp = 1)
        channels.replaceCatalogue(other, listOf(fromProvider(other, "b")), syncStamp = 1)
        val a = stored(home, "a")
        channels.setHidden(a.id, hidden = true)
        channels.setSortIndex(a.id, 3)
        channels.setCustomName(a.id, "Renamed")
        channels.setEpgOverride(a.id, "override.guide")
        channels.setMatchedEpgIds(listOf(ChannelEpgMatch(a.id, "matched.guide")))

        assertThat(channels.userStateForSource(home)).containsExactly(
            ChannelUserState(
                streamId = "a",
                id = a.id,
                hidden = true,
                sortIndex = 3,
                customName = "Renamed",
                epgOverrideId = "override.guide",
                matchedEpgId = "matched.guide",
            ),
        )
    }
}
