/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv

import android.app.AlarmManager
import android.app.Application
import android.content.Intent
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import app.opentv.core.ServiceLocator
import app.opentv.data.model.Recording
import app.opentv.data.model.RecordingStatus
import app.opentv.data.model.Reminder
import app.opentv.recording.RecordingScheduler
import app.opentv.reminders.ReminderScheduler
import com.google.common.truth.Truth.assertWithMessage
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * An update drops every alarm the app set, and the app's process doesn't start again until the viewer
 * opens it, so a booking or reminder due in between was missed (#336). The broadcast goes to the app's
 * package the way Android sends it, so the manifest's intent filters are part of what's checked.
 */
// Plain Application: OpenTvApp sets alarms itself on start, which would hide whether the receivers did.
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class AlarmsAfterUpdateTest {

    private val app: Application = ApplicationProvider.getApplicationContext()
    private val graph = ServiceLocator.get(app)
    private val alarms = shadowOf(app.getSystemService(AlarmManager::class.java))
    private val inAnHour = System.currentTimeMillis() + 60 * 60 * 1000L

    private var bookingId = 0L
    private var reminderId = 0L

    @After
    fun forget() = runBlocking {
        if (bookingId > 0) graph.recordingRepository.delete(bookingId)
        if (reminderId > 0) graph.reminderRepository.delete(reminderId)
    }

    private fun updateInstalled() {
        app.sendBroadcast(Intent(Intent.ACTION_MY_PACKAGE_REPLACED).setPackage(app.packageName))
        shadowOf(Looper.getMainLooper()).idle()
    }

    /** The receivers set alarms off the main thread, so this waits a little for one to appear. */
    private fun alarmIsSet(action: String, extra: String, id: Long): Boolean {
        val deadline = System.currentTimeMillis() + 5_000
        while (System.currentTimeMillis() < deadline) {
            val set = alarms.scheduledAlarms.any { alarm ->
                val intent = shadowOf(alarm.operation).savedIntent
                intent.action == action && intent.getLongExtra(extra, -1L) == id
            }
            if (set) return true
            Thread.sleep(20)
            shadowOf(Looper.getMainLooper()).idle()
        }
        return false
    }

    @Test
    fun `an upcoming booking's alarm is set again when an update installs`() {
        bookingId = runBlocking {
            graph.recordingRepository.insert(
                Recording(
                    channelId = 1,
                    sourceId = 1,
                    channelName = "BBC One",
                    title = "The News",
                    filePath = "/tmp/news.ts",
                    streamUrl = "https://example.com/1",
                    scheduledStartMillis = inAnHour,
                    scheduledEndMillis = inAnHour + 30 * 60 * 1000L,
                    status = RecordingStatus.SCHEDULED,
                ),
            )
        }

        updateInstalled()

        assertWithMessage("the booking's alarm")
            .that(alarmIsSet(RecordingScheduler.ACTION_START, "recordingId", bookingId))
            .isTrue()
    }

    @Test
    fun `an upcoming reminder's alarm is set again when an update installs`() {
        reminderId = runBlocking {
            graph.database.reminders().insert(
                Reminder(
                    channelId = 1,
                    source = "Home",
                    streamId = "1",
                    channelName = "BBC One",
                    title = "The News",
                    startUtcMillis = inAnHour,
                    endUtcMillis = inAnHour + 30 * 60 * 1000L,
                ),
            )
        }

        updateInstalled()

        assertWithMessage("the reminder's alarm")
            .that(alarmIsSet(ReminderScheduler.ACTION_REMIND, "reminderId", reminderId))
            .isTrue()
    }
}
