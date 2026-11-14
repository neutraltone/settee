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

    @Test
    fun `what the recorder wrote while the NAS was unreachable still plays once the capture ends`() {
        val nas = GrowingFile(recording(2 * MB), visible = MB)
        clock.at(12_000) { nas.visible += MB }
        clock.at(13_000) { RecordingLiveState.markInactive(RECORDING_ID) }
        var opens = 0

        val delivered = watchWhileRecording {
            if (++opens == 2) throw IOException("NAS unreachable")
            nas.handle()
        }

        assertThat(delivered).isEqualTo(nas.bytes)
    }

    @Test
    fun `a connection that drops is reconnected straight away, not after a long wait`() {
        val nas = GrowingFile(recording(2 * MB), visible = MB)
        clock.at(4_000) { nas.visible += MB }
        clock.at(30_000) { RecordingLiveState.markInactive(RECORDING_ID) }
        val openedAt = mutableListOf<Long>()

        val delivered = watchWhileRecording {
            openedAt += clock.now
            if (openedAt.size == 1) nas.handle(dead = { clock.now >= 3_000 }) else nas.handle()
        }

        assertThat(delivered).isEqualTo(nas.bytes)
        assertThat(openedAt).hasSize(2)
        assertThat(openedAt[1]).isLessThan(4_000L)
    }

    @Test
    fun `on a server that caches the end of file, playback catches up every couple of seconds once that's known`() {
        val nas = GrowingFile(recording(STEPS * MB + MB / 2), visible = MB / 2)
        (1..STEPS).forEach { step -> clock.at(step * 3_000L) { nas.visible += MB } }
        clock.at(STEPS * 3_000L + 1_000) { RecordingLiveState.markInactive(RECORDING_ID) }
        val openedAt = mutableListOf<Long>()

        val delivered = watchWhileRecording {
            openedAt += clock.now
            nas.handle(cachesEnd = true)
        }

        assertThat(delivered).isEqualTo(nas.bytes)
        // The first reopen, after the long stall, is what shows the server caches: the rest come sooner.
        assertThat(openedAt.size).isAtLeast(4)
        assertThat(openedAt.drop(1).zipWithNext { a, b -> b - a }.max()).isAtMost(2_000L)
    }

    @Test
    fun `closing the player while it reconnects closes the new connection too`() {
        val nas = GrowingFile(recording(MB), visible = MB)
        RecordingLiveState.markActive(RECORDING_ID, "smb://nas.example.com/recordings/live.ts")
        lateinit var source: GrowingRecordingDataSource
        val handles = mutableListOf<GrowingFile.Handle>()
        source = GrowingRecordingDataSource(
            ApplicationProvider.getApplicationContext(),
            openSmb = {
                // The viewer leaves while the second connection is being made.
                if (handles.isNotEmpty()) source.close()
                nas.handle().also { handles += it }
            },
            sleep = clock::sleep,
        )
        source.open(DataSpec(Uri.parse("optvrec://$RECORDING_ID")))

        source.readAll()
        source.close()

        assertThat(handles).hasSize(2)
        assertThat(handles.map { it.closed }).containsExactly(true, true)
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
        /**
         * An open handle on the file. One that [cachesEnd] shows only what there was when it was opened,
         * as some servers do; once [dead] says so, its connection has dropped and every request fails.
         */
        fun handle(cachesEnd: Boolean = false, dead: () -> Boolean = { false }) = Handle(cachesEnd, dead)

        inner class Handle(private val cachesEnd: Boolean, private val dead: () -> Boolean) : SmbReader {
            private val openedAt = visible
            private val end: Int get() = if (cachesEnd) openedAt else visible
            var closed = false

            override val length: Long = openedAt.toLong()

            override fun endOfFile(): Long {
                if (dead()) throw IOException("connection dropped")
                return end.toLong()
            }

            override fun readAt(buffer: ByteArray, fileOffset: Long, bufferOffset: Int, length: Int): Int {
                if (dead()) throw IOException("connection dropped")
                val available = end - fileOffset
                if (available <= 0) return -1
                val n = minOf(length.toLong(), available).toInt()
                System.arraycopy(bytes, fileOffset.toInt(), buffer, bufferOffset, n)
                return n
            }

            override fun close() {
                closed = true
            }
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
