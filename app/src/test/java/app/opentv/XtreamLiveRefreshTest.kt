/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import app.opentv.data.model.ChannelEpgMatch
import app.opentv.data.repo.CatalogRepository.SyncResult
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * An Xtream provider's live channels are saved from a download a batch at a time, as movies and shows
 * are, so a very large line-up never has to fit in a Fire TV Stick's heap at once.
 */
// Plain Application: OpenTvApp schedules WorkManager on start, which the database doesn't need.
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class XtreamLiveRefreshTest {

    private val test = TestCatalog(ApplicationProvider.getApplicationContext())
    private val channels = test.db.channels()
    private val panel = test.panel

    init {
        panel.replies[""] = """{"user_info": {"status": "Active"}}"""
    }

    @After
    fun close() = test.close()

    @Test
    fun `a line-up of thousands of channels is saved whole`() = runTest {
        test.db.sources().insert(PANEL_SOURCE)
        // Cleaned like any channel, though it's in a late batch.
        panel.replies["get_live_streams"] =
            lineUp(0 until LARGE) { if (it == 4321) "UK| BBC ONE FHD" else "Channel $it" }

        val result = test.catalog.fetchLive(PANEL_SOURCE, nowUtcMillis = 1_000)

        assertThat(result).isEqualTo(SyncResult.Success(LARGE, 0, 0))
        val saved = channels.forSource(PANEL_SOURCE.id)
        assertThat(saved).hasSize(LARGE)
        assertThat(saved.map { it.streamId }.toSet()).isEqualTo((0 until LARGE).map { "$it" }.toSet())
        val late = saved.single { it.streamId == "4321" }
        assertThat(late.displayName).isEqualTo("BBC ONE")
        assertThat(late.qualityLabel).isEqualTo("FHD")
    }

    @Test
    fun `a refresh keeps what the viewer set on each channel and drops the ones the provider did`() = runTest {
        test.db.sources().insert(PANEL_SOURCE)
        panel.replies["get_live_streams"] = lineUp(0 until 1_200)
        test.catalog.fetchLive(PANEL_SOURCE, nowUtcMillis = 1_000)
        // In the last batch, so it's merged after others have been saved.
        val late = stored("1150")
        channels.setHidden(late.id, true)
        channels.setSortIndex(late.id, 7)
        channels.setCustomName(late.id, "My channel")
        channels.setEpgOverride(late.id, "bbc1.uk")
        channels.setMatchedEpgIds(listOf(ChannelEpgMatch(late.id, "bbc.one.uk")))

        panel.replies["get_live_streams"] = lineUp(1 until 1_200) { "Renamed $it" }
        val result = test.catalog.fetchLive(PANEL_SOURCE, nowUtcMillis = 2_000)

        assertThat(result).isEqualTo(SyncResult.Success(1_199, 0, 0))
        val kept = stored("1150")
        assertThat(kept.id).isEqualTo(late.id)
        assertThat(kept.name).isEqualTo("Renamed 1150")
        assertThat(kept.hidden).isTrue()
        assertThat(kept.sortIndex).isEqualTo(7)
        assertThat(kept.customName).isEqualTo("My channel")
        assertThat(kept.epgOverrideId).isEqualTo("bbc1.uk")
        assertThat(kept.matchedEpgId).isEqualTo("bbc.one.uk")
        assertThat(channels.forSource(PANEL_SOURCE.id).map { it.streamId }).doesNotContain("0")
        assertThat(channels.forSource(PANEL_SOURCE.id)).hasSize(1_199)
    }

    @Test
    fun `a channel the provider brings back in a late batch comes back hidden if the viewer hid it`() = runTest {
        test.db.sources().insert(PANEL_SOURCE)
        panel.replies["get_live_streams"] = lineUp(0 until 1_200)
        test.catalog.fetchLive(PANEL_SOURCE, nowUtcMillis = 1_000)
        channels.changeHidden(stored("1150").id, hidden = true, changedAtMillis = 1_500)
        panel.replies["get_live_streams"] = lineUp(0 until 1_100)
        test.catalog.fetchLive(PANEL_SOURCE, nowUtcMillis = 2_000)

        panel.replies["get_live_streams"] = lineUp(0 until 1_200)
        test.catalog.fetchLive(PANEL_SOURCE, nowUtcMillis = 3_000)

        assertThat(stored("1150").hidden).isTrue()
        assertThat(stored("1149").hidden).isFalse()
    }

    @Test
    fun `a line-up cut off after some batches leaves every channel as it was`() = runTest {
        test.db.sources().insert(PANEL_SOURCE)
        panel.replies["get_live_streams"] = lineUp(0 until 1_200)
        test.catalog.fetchLive(PANEL_SOURCE, nowUtcMillis = 1_000)
        channels.setCustomName(stored("5").id, "My channel")

        // Two full batches of a new line-up, then the reply breaks off.
        panel.replies["get_live_streams"] = lineUp(5_000 until 6_100) { "New $it" }.dropLast(40)
        val result = test.catalog.fetchLive(PANEL_SOURCE, nowUtcMillis = 2_000)

        assertThat(result).isInstanceOf(SyncResult.Failed::class.java)
        val after = channels.forSource(PANEL_SOURCE.id)
        assertThat(after.map { it.streamId }.toSet()).isEqualTo((0 until 1_200).map { "$it" }.toSet())
        assertThat(after.all { it.lastSeenMillis == 1_000L }).isTrue()
        assertThat(stored("5").customName).isEqualTo("My channel")
    }

    @Test
    fun `an empty line-up says there are no channels and deletes none`() = runTest {
        test.db.sources().insert(PANEL_SOURCE)
        panel.replies["get_live_streams"] = lineUp(0 until 3)
        test.catalog.fetchLive(PANEL_SOURCE, nowUtcMillis = 1_000)

        panel.replies["get_live_streams"] = "[]"
        val result = test.catalog.fetchLive(PANEL_SOURCE, nowUtcMillis = 2_000)

        assertThat((result as SyncResult.Failed).reason.sentence).isEqualTo(
            ApplicationProvider.getApplicationContext<Context>().getString(R.string.error_no_channels),
        )
        assertThat(channels.forSource(PANEL_SOURCE.id)).hasSize(3)
    }

    private suspend fun stored(streamId: String) =
        channels.forSource(PANEL_SOURCE.id).single { it.streamId == streamId }

    private companion object {
        /** Several batches' worth, with a part batch at the end. */
        const val LARGE = 5_250

        /** A `get_live_streams` reply listing a channel for each of [ids]. */
        fun lineUp(ids: Iterable<Int>, name: (Int) -> String = { "Channel $it" }) =
            ids.joinToString(",", "[", "]") { """{"stream_id": "$it", "name": "${name(it)}"}""" }
    }
}
