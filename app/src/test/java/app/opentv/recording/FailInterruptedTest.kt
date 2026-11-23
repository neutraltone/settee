/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv.recording

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import app.opentv.PANEL_SOURCE
import app.opentv.R
import app.opentv.core.ViewerError
import app.opentv.data.model.Recording
import app.opentv.data.model.RecordingStatus
import app.opentv.recording.RecordingFixture.Companion.HOUR
import app.opentv.recording.RecordingFixture.Companion.NOW
import app.opentv.testing
import com.google.common.truth.Truth.assertThat
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * A recording still in progress when Settee starts again was cut off when Settee closed: an update
 * installed anyway, Android killing it for memory, or the power going. It says so, in a sentence
 * with a detail line, like every other recording failure (#474).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class FailInterruptedTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val fixture = RecordingFixture(context)
    private val recordings = fixture.recordings

    @After
    fun close() = fixture.close()

    private suspend fun recording(status: RecordingStatus, error: String? = null): Long = recordings.insert(
        Recording(
            channelId = fixture.bbcOne.id,
            sourceId = PANEL_SOURCE.id,
            channelName = "BBC One",
            title = "Countryfile",
            filePath = "/recordings/countryfile-$status.ts",
            streamUrl = "",
            scheduledStartMillis = NOW - HOUR,
            scheduledEndMillis = NOW + HOUR,
            status = status,
            error = error,
        ),
    )

    /** What OpenTvApp does on a cold start. */
    private suspend fun coldStart() = recordings.failInterrupted(RecordingEngine.Interrupted.stored(context))

    @Test
    fun `a recording cut off when Settee closed says so, with Interrupted as the detail`() = testing {
        val id = recording(RecordingStatus.RECORDING)

        coldStart()

        val row = recordings.byId(id)!!
        assertThat(row.status).isEqualTo(RecordingStatus.FAILED)
        assertThat(ViewerError.fromStored(row.error!!)).isEqualTo(
            ViewerError(context.getString(R.string.rec_error_interrupted), "Interrupted"),
        )
        assertThat(
            context.getString(R.string.rec_error_interrupted),
        ).isNotEqualTo(context.getString(R.string.rec_error_failed))
    }

    @Test
    fun `bookings, finished recordings and ones that already failed are left as they were`() = testing {
        val booked = recording(RecordingStatus.SCHEDULED)
        val finished = recording(RecordingStatus.COMPLETED)
        val channelStopped = ViewerError("The channel stopped.", "HTTP 404")
        val failed = recording(RecordingStatus.FAILED, error = channelStopped.stored())

        coldStart()

        assertThat(recordings.byId(booked)!!.status).isEqualTo(RecordingStatus.SCHEDULED)
        assertThat(recordings.byId(finished)!!.status).isEqualTo(RecordingStatus.COMPLETED)
        assertThat(recordings.byId(failed)!!.status).isEqualTo(RecordingStatus.FAILED)
        assertThat(ViewerError.fromStored(recordings.byId(failed)!!.error!!)).isEqualTo(channelStopped)
    }
}
