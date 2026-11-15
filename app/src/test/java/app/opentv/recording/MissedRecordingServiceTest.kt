/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv.recording

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import app.opentv.R
import app.opentv.core.ServiceLocator
import app.opentv.core.ViewerError
import app.opentv.data.model.Recording
import app.opentv.data.model.RecordingStatus
import com.google.common.truth.Truth.assertThat
import java.io.File
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * An alarm can fire after its booking's window has ended, by any route. The capture gives up before
 * it touches storage, and the recording is marked missed, not "Nothing was recorded" (#371).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class MissedRecordingServiceTest {

    private val app: Application = ApplicationProvider.getApplicationContext()
    private val graph = ServiceLocator.get(app)
    private val recordings = graph.recordingRepository

    @Test
    fun `a late alarm for a booking that has ended writes nothing and is marked missed`() = runBlocking {
        val file = File(RecordingStorage.internalDir(app), "missed.ts")
        val now = System.currentTimeMillis()
        val id = recordings.insert(
            Recording(
                channelId = 1,
                sourceId = 1,
                channelName = "ITV",
                title = "Countryfile",
                filePath = file.absolutePath,
                streamUrl = "",
                scheduledStartMillis = now - 2 * HOUR,
                scheduledEndMillis = now - HOUR,
                status = RecordingStatus.SCHEDULED,
            ),
        )

        RecordingService.start(app, id)
        val start = shadowOf(app).nextStartedService
        Robolectric.buildService(RecordingService::class.java, start).create().startCommand(0, 1)
        val row = withTimeout(5_000) {
            var row = recordings.byId(id)!!
            while (row.status == RecordingStatus.SCHEDULED || row.status == RecordingStatus.RECORDING) {
                delay(20)
                row = recordings.byId(id)!!
            }
            row
        }

        assertThat(row.status).isEqualTo(RecordingStatus.FAILED)
        assertThat(ViewerError.fromStored(row.error!!)?.sentence).isEqualTo(app.getString(R.string.rec_error_missed))
        assertThat(row.startedAtMillis).isEqualTo(0)
        assertThat(file.exists()).isFalse()
    }

    private companion object {
        const val HOUR = 3_600_000L
    }
}
