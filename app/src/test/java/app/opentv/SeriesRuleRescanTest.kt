/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import app.opentv.core.AppSettings
import app.opentv.data.model.Channel
import app.opentv.data.model.Programme
import app.opentv.data.model.SeriesRule
import app.opentv.data.remote.StalkerApi
import app.opentv.data.remote.XtreamApi
import app.opentv.data.repo.EpgRepository
import app.opentv.data.repo.RecordingRepository
import app.opentv.data.repo.SourceRepository
import app.opentv.recording.RecordingEngine
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** A series rule books from the guide the guide shows for its Channel, on every rescan (#104). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class SeriesRuleRescanTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val test = TestCatalog(context)
    private val db = test.db
    private val recordings = RecordingRepository(db.recordings(), db.seriesRules())
    private val engine = RecordingEngine(
        context,
        recordings,
        SourceRepository(db.sources(), XtreamApi(test.panel.http), StalkerApi(test.panel.http)),
        AppSettings.get(context),
        EpgRepository(
            programmeDao = db.programmes(),
            feedDao = db.epgFeeds(),
            aliasDao = db.epgAliases(),
            channelDao = db.channels(),
            sourceDao = db.sources(),
            api = XtreamApi(test.panel.http),
            http = test.panel.http,
        ),
        test.lineup,
    )

    @After
    fun close() = test.close()

    private fun itv(streamId: String, name: String, rank: Int, epgId: String) = Channel(
        sourceId = PANEL_SOURCE.id,
        streamId = streamId,
        name = name,
        displayName = "ITV",
        groupKey = "uk:itv",
        qualityRank = rank,
        categoryId = null,
        logoUrl = null,
        epgChannelId = epgId,
        number = null,
        streamUrl = "http://example.com/live/u/p/$streamId.ts",
    )

    private fun airing(epgId: String, start: Long) = Programme(
        feedId = 1,
        epgChannelId = epgId,
        startUtcMillis = start,
        endUtcMillis = start + HOUR,
        title = "Countryfile",
    )

    @Test
    fun `a rule set on any quality rescans the channel's own guide`() = runTest {
        db.sources().insert(PANEL_SOURCE)
        db.channels().upsertAll(listOf(itv("1", "UK| ITV FHD", 300, "itv.fhd"), itv("2", "UK| ITV HD", 200, "itv.hd")))
        val hd = db.channels().allForMatching().single { it.streamId == "2" }
        val tomorrow = System.currentTimeMillis() + DAY
        db.programmes().upsertAll(listOf(airing("itv.fhd", tomorrow), airing("itv.hd", tomorrow + HOUR)))
        db.seriesRules().insert(
            SeriesRule(
                channelId = hd.id,
                channelName = "ITV",
                titleKey = "countryfile",
                title = "Countryfile",
                createdAtMillis = 0,
            ),
        )

        engine.rescanSeriesRules()

        val padding = 60_000L * AppSettings.get(context).recordPadStartMinutes.value
        assertThat(recordings.scheduled().map { it.scheduledStartMillis }).containsExactly(tomorrow - padding)
    }

    private companion object {
        const val HOUR = 60 * 60 * 1000L
        const val DAY = 24 * HOUR
    }
}
