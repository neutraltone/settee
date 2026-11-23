/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv.recording

import android.app.Application
import app.opentv.R
import app.opentv.TestGraph
import app.opentv.core.AppSettings
import app.opentv.core.ViewerError
import app.opentv.data.model.Recording
import app.opentv.data.model.RecordingStatus
import app.opentv.recording.RecordingFixture.Companion.HOUR
import com.google.common.truth.Truth.assertThat
import java.io.File
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Rule
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

    // Swapped in for the app's graph, which the service reaches for.
    @get:Rule
    val testGraph = TestGraph()

    private val app = testGraph.app
    private val graph = testGraph.graph
    private val recordings = graph.recordingRepository

    @Test
    fun `a late alarm for a booking that has ended writes nothing and says the alarm was late`() = runBlocking {
        // Internal, whatever an earlier test left set: the file checked for below is where the
        // sink would have written it.
        graph.settings.setRecordingTarget(AppSettings.RecordingTarget.INTERNAL)
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
        // The capture runs on the graph's io dispatcher: settled, it has finished.
        testGraph.awaitIdle()
        val row = recordings.observeAll().first().single { it.id == id }

        assertThat(row.status).isEqualTo(RecordingStatus.FAILED)
        assertThat(
            ViewerError.fromStored(row.error!!)?.sentence,
        ).isEqualTo(app.getString(R.string.rec_error_missed_late))
        assertThat(row.startedAtMillis).isEqualTo(0)
        assertThat(file.exists()).isFalse()
    }
}
