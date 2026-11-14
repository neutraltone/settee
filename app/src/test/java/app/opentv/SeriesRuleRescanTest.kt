/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import app.opentv.core.AppSettings
import app.opentv.core.ErrorWording
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
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
            wording = ErrorWording(ApplicationProvider.getApplicationContext(), onTelevision = false),
            selfEnabledGuide = EpgRepository.SelfEnabledGuide.InMemory(),
            invalidationTracker = db.invalidationTracker,
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
        val hd = db.channels().everyRow().single { it.streamId == "2" }
        val tomorrow = System.currentTimeMillis() + DAY
        db.programmes().insertOrReplace(listOf(airing("itv.fhd", tomorrow), airing("itv.hd", tomorrow + HOUR)))
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

    @Test
    fun `booking one rule from several places at once books each airing once`() = runTest {
        db.sources().insert(PANEL_SOURCE)
        db.channels().upsertAll(listOf(itv("2", "UK| ITV HD", 200, "itv.hd")))
        val channel = db.channels().everyRow().single()
        val tomorrow = System.currentTimeMillis() + DAY
        val airings = List(20) { airing("itv.hd", tomorrow + it * HOUR) }

        withContext(Dispatchers.Default) {
            List(8) { async { engine.scheduleMatching(channel, "countryfile", ruleId = 1, airings) } }.awaitAll()
        }

        assertThat(recordings.scheduled()).hasSize(20)
    }

    @Test
    fun `recording a series from the guide records an airing on now once, though two feeds list it`() = runTest {
        db.sources().insert(PANEL_SOURCE)
        db.channels().upsertAll(listOf(itv("2", "UK| ITV HD", 200, "itv.hd")))
        val channel = db.channels().everyRow().single()
        val onNow = airing("itv.hd", System.currentTimeMillis() - HOUR / 2)
        db.programmes().insertOrReplace(listOf(onNow, onNow.copy(feedId = 2)))

        engine.recordSeries(channel, "Countryfile")

        assertThat(recordings.active()).hasSize(1)
    }

    @Test
    fun `recording a series from the guide books its later airings from the guide, past the day on show`() = runTest {
        db.sources().insert(PANEL_SOURCE)
        db.channels().upsertAll(listOf(itv("2", "UK| ITV HD", 200, "itv.hd")))
        val channel = db.channels().everyRow().single()
        val nextWeek = System.currentTimeMillis() + 7 * DAY
        db.programmes().insertOrReplace(listOf(airing("itv.hd", nextWeek).copy(description = "Farming.")))

        engine.recordSeries(channel, "Countryfile")

        assertThat(recordings.scheduled().map { it.description }).containsExactly("Farming.")
    }

    private companion object {
        const val HOUR = 60 * 60 * 1000L
        const val DAY = 24 * HOUR
    }
}
