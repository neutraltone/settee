/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.test.core.app.ApplicationProvider
import app.opentv.player.GrowingRecordingDataSource
import app.opentv.recording.RecordingLiveState
import app.opentv.recording.SmbReader
import com.google.common.truth.Truth.assertThat
import java.io.ByteArrayOutputStream
import java.io.IOException
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Watching a recording on the NAS while it records keeps the one connection it opened, rather than
 * reconnecting each time the recorder's next megabyte is slow to land (#277). Time here is the fake
 * [clock]'s, so waiting a minute for a capture costs nothing.
 */
@UnstableApi
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class GrowingNasRecordingTest {

    private val clock = FakeClock()

    @After
    fun finishRecording() {
        RecordingLiveState.markInactive(RECORDING_ID)
    }

    @Test
    fun `a recording that grows a megabyte every few seconds plays to its end on one connection`() {
        val nas = GrowingFile(recording(STEPS * MB + MB / 2), visible = MB / 2)
        // The recorder's buffered stream lands a megabyte every 3 s; the capture ends after the last.
        (1..STEPS).forEach { step -> clock.at(step * 3_000L) { nas.visible += MB } }
        clock.at(STEPS * 3_000L + 1_000) { RecordingLiveState.markInactive(RECORDING_ID) }
        var opens = 0

        val delivered = watchWhileRecording {
            opens++
            nas.handle()
        }

        assertThat(delivered).isEqualTo(nas.bytes)
        assertThat(opens).isEqualTo(1)
    }

    @Test
    fun `a connection lost while recording is retried until the capture ends, not given up on`() {
        val nas = GrowingFile(recording(MB), visible = MB)
        clock.at(60_000) { RecordingLiveState.markInactive(RECORDING_ID) }
        var opens = 0
        var endedAt = -1L

        val delivered = watchWhileRecording(onEnd = { endedAt = clock.now }) {
            if (++opens > 1) throw IOException("NAS unreachable")
            nas.handle()
        }

        assertThat(delivered).isEqualTo(nas.bytes)
        assertThat(opens).isGreaterThan(1)
        assertThat(endedAt).isAtLeast(60_000L)
    }

    @Test
    fun `a recording whose capture has finished plays to its end straight away`() {
        val nas = GrowingFile(recording(2 * MB), visible = 2 * MB)
        var opens = 0

        val delivered = watchWhileRecording(finishedBeforeReading = true) {
            opens++
            nas.handle()
        }

        assertThat(delivered).isEqualTo(nas.bytes)
        assertThat(opens).isEqualTo(1)
        assertThat(clock.now).isEqualTo(0L)
    }

    private fun watchWhileRecording(
        finishedBeforeReading: Boolean = false,
        onEnd: () -> Unit = {},
        open: () -> SmbReader,
    ): ByteArray {
        RecordingLiveState.markActive(RECORDING_ID, "smb://nas.example.com/recordings/live.ts")
        val source = GrowingRecordingDataSource(
            ApplicationProvider.getApplicationContext(),
            openSmb = { open() },
            sleep = clock::sleep,
        )
        source.open(DataSpec(Uri.parse("optvrec://$RECORDING_ID")))
        if (finishedBeforeReading) RecordingLiveState.markInactive(RECORDING_ID)
        return source.readAll().also {
            onEnd()
            source.close()
        }
    }

    private fun DataSource.readAll(): ByteArray {
        val out = ByteArrayOutputStream()
        val chunk = ByteArray(EXTRACTOR_READ)
        while (true) {
            val read = read(chunk, 0, chunk.size)
            if (read == C.RESULT_END_OF_INPUT) return out.toByteArray()
            out.write(chunk, 0, read)
        }
    }

    /** Time that passes only when the source sleeps, running what was due at each moment. */
    private class FakeClock {
        var now = 0L
        private val due = mutableListOf<Pair<Long, () -> Unit>>()

        fun at(millis: Long, event: () -> Unit) {
            due += millis to event
        }

        fun sleep(millis: Long) {
            now += millis
            check(now < 10 * 60_000L) { "still waiting after ten minutes" }
            due.filter { it.first <= now }.forEach { event ->
                due -= event
                event.second()
            }
        }
    }

    /** A file on the NAS of which the first [visible] bytes have been written, read through any handle. */
    private class GrowingFile(val bytes: ByteArray, var visible: Int) {
        fun handle(): SmbReader = object : SmbReader {
            override val length: Long = visible.toLong()

            override fun endOfFile(): Long = visible.toLong()

            override fun readAt(buffer: ByteArray, fileOffset: Long, bufferOffset: Int, length: Int): Int {
                val available = visible - fileOffset
                if (available <= 0) return -1
                val n = minOf(length.toLong(), available).toInt()
                System.arraycopy(bytes, fileOffset.toInt(), buffer, bufferOffset, n)
                return n
            }

            override fun close() = Unit
        }
    }

    private companion object {
        const val RECORDING_ID = 43L

        const val MB = 1024 * 1024

        /** Megabytes the recorder adds while it's watched. */
        const val STEPS = 5

        /** What Media3's TsExtractor asks for at a time: 50 transport-stream packets. */
        const val EXTRACTOR_READ = 50 * 188

        /** Bytes that differ by position, so a wrong offset shows up as a mismatch. */
        fun recording(size: Int): ByteArray = ByteArray(size) { (it * 31 + it / 251).toByte() }
    }
}
