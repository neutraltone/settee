/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv.recording

import android.app.AlarmManager
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import app.opentv.PANEL_SOURCE
import app.opentv.R
import app.opentv.TestCatalog
import app.opentv.core.AppSettings
import app.opentv.core.ErrorWording
import app.opentv.core.ViewerError
import app.opentv.data.model.Recording
import app.opentv.data.model.RecordingStatus
import app.opentv.data.remote.StalkerApi
import app.opentv.data.remote.XtreamApi
import app.opentv.data.repo.EpgRepository
import app.opentv.data.repo.RecordingRepository
import app.opentv.data.repo.SourceRepository
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * Bookings are re-armed on every launch, because alarms don't survive a force-stop or an update. One
 * whose whole window passed while Settee wasn't running is missed, not started late (#371).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class RearmScheduledTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val test = TestCatalog(context)
    private val db = test.db
    private val settings = AppSettings.get(context)
    private val recordings = RecordingRepository(db.recordings(), db.seriesRules())
    private val engine = RecordingEngine(
        context,
        recordings,
        SourceRepository(db.sources(), XtreamApi(test.panel.http), StalkerApi(test.panel.http), test.panel.http),
        settings,
        EpgRepository(
            programmeDao = db.programmes(),
            refreshDao = db.guideRefresh(),
            feedDao = db.epgFeeds(),
            aliasDao = db.epgAliases(),
            channelDao = db.channels(),
            sourceDao = db.sources(),
            api = XtreamApi(test.panel.http),
            http = test.panel.http,
            wording = ErrorWording(context, onTelevision = false),
            selfEnabledGuide = EpgRepository.SelfEnabledGuide.InMemory(),
        ),
        test.lineup,
        ErrorWording(context, onTelevision = false),
        clock = { NOW },
    )
    private val alarms = shadowOf(context.getSystemService(Context.ALARM_SERVICE) as AlarmManager)

    @Before
    fun autoSwitchOn() = settings.setRecordAutoSwitch(true)

    @After
    fun close() = test.close()

    private suspend fun booking(start: Long, end: Long): Long = recordings.insert(
        Recording(
            channelId = 1,
            sourceId = PANEL_SOURCE.id,
            channelName = "ITV",
            title = "Countryfile",
            filePath = "/recordings/countryfile.ts",
            streamUrl = "",
            scheduledStartMillis = start,
            scheduledEndMillis = end,
            status = RecordingStatus.SCHEDULED,
        ),
    )

    /** When each alarm for [id] is set to go off, by the action it carries. */
    private fun armed(id: Long): Map<String, Long> = alarms.scheduledAlarms
        .filter { RecordingScheduler.recordingIdFrom(shadowOf(it.operation).savedIntent) == id }
        .associate { shadowOf(it.operation).savedIntent.action!! to it.triggerAtMs }

    @Test
    fun `a booking that ended while Settee wasn't running is missed, not armed`() = runTest {
        val id = booking(start = NOW - 2 * HOUR, end = NOW - HOUR)

        engine.rearmScheduled()

        assertThat(armed(id)).isEmpty()
        val row = recordings.byId(id)!!
        assertThat(row.status).isEqualTo(RecordingStatus.FAILED)
        assertThat(
            ViewerError.fromStored(row.error!!)?.sentence,
        ).isEqualTo(context.getString(R.string.rec_error_missed))
        assertThat(
            context.getString(R.string.rec_error_missed),
        ).isNotEqualTo(context.getString(R.string.rec_error_channel_stopped))
    }

    @Test
    fun `a booking that's on now, or has no end, starts straight away`() = runTest {
        val onNow = booking(start = NOW - HOUR, end = NOW + HOUR)
        val noEnd = booking(start = NOW - HOUR, end = 0)

        engine.rearmScheduled()

        assertThat(armed(onNow)).containsExactly(RecordingScheduler.ACTION_START, NOW + 1_000L)
        assertThat(armed(noEnd)).containsExactly(RecordingScheduler.ACTION_START, NOW + 1_000L)
        assertThat(recordings.byId(onNow)!!.status).isEqualTo(RecordingStatus.SCHEDULED)
        assertThat(recordings.byId(noEnd)!!.status).isEqualTo(RecordingStatus.SCHEDULED)
    }

    @Test
    fun `a future booking is armed at its start, with its warning before it`() = runTest {
        val later = booking(start = NOW + HOUR, end = NOW + 2 * HOUR)

        engine.rearmScheduled()

        assertThat(armed(later)).containsExactly(
            RecordingScheduler.ACTION_START,
            NOW + HOUR,
            RecordingScheduler.ACTION_WARN,
            NOW + HOUR - RecordingScheduler.WARN_LEAD_MILLIS,
        )
        assertThat(recordings.byId(later)!!.status).isEqualTo(RecordingStatus.SCHEDULED)
    }

    private companion object {
        const val HOUR = 3_600_000L

        /** A fixed instant well away from the real clock, so nothing here passes by accident of timing. */
        const val NOW = 1_700_000_000_000L
    }
}
