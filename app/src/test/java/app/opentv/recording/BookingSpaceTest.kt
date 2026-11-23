/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv.recording

import androidx.test.core.app.ApplicationProvider
import app.opentv.TestCatalog
import app.opentv.data.model.Recording
import app.opentv.data.model.RecordingStatus
import app.opentv.data.repo.RecordingRepository
import com.google.common.truth.Truth.assertThat
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * A NAS can take as long as the network does to say how full it is, or never answer. That mustn't
 * hold up saying a booking elsewhere may not fit (#473).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class BookingSpaceTest {

    private val test = TestCatalog(ApplicationProvider.getApplicationContext())
    private val recordings = RecordingRepository(test.db.recordings(), test.db.seriesRules())

    /** Lets the NAS answer, once a test is done with it. */
    private val nasAnswers = CountDownLatch(1)

    /** This device is full; the NAS has plenty of room, when it gets round to saying so. */
    private val space = BookingSpace(recordings, test.db.channels(), { store ->
        if (store is RecordingStore.Nas) {
            nasAnswers.await(20, TimeUnit.SECONDS)
            100 * GB
        } else {
            0L
        }
    }, clock = { NOW })

    @After
    fun close() {
        nasAnswers.countDown()
        test.close()
    }

    @Test
    fun `a NAS slow to say how full it is doesn't hold up the note for a booking on this device`() = runBlocking<Unit> {
        val all = listOf(booking(1, "smb://nas/tv/Recordings/a.ts"), booking(2, "/recordings/b.ts"))

        val notes = withTimeout(5_000) { space.shortOfRoom(all).first { 2L in it } }

        assertThat(notes).containsExactly(2L, 0L)
    }

    @Test
    fun `a booking's room is worked out without asking a NAS it isn't on`() = runBlocking<Unit> {
        recordings.insert(booking(1, "smb://nas/tv/Recordings/a.ts"))
        recordings.insert(booking(2, "/recordings/b.ts"))

        val room = withTimeout(5_000) { space.roomFor(2) }

        assertThat(room).isEqualTo(0L)
    }

    private fun booking(id: Long, filePath: String) = Recording(
        id = id,
        channelId = 7,
        sourceId = 1,
        channelName = "Channel 7",
        title = "Booking $id",
        filePath = filePath,
        streamUrl = "",
        scheduledStartMillis = NOW + HOUR,
        scheduledEndMillis = NOW + 2 * HOUR,
        status = RecordingStatus.SCHEDULED,
    )

    private companion object {
        const val NOW = 1_800_000_000_000L
        const val HOUR = 3_600_000L
        const val GB = 1_000_000_000L
    }
}
