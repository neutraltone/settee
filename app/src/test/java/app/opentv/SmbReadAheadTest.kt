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
import androidx.media3.datasource.TransferListener
import androidx.test.core.app.ApplicationProvider
import app.opentv.player.GrowingRecordingDataSource
import app.opentv.player.SmbDataSource
import app.opentv.recording.RecordingLiveState
import app.opentv.recording.SmbReader
import com.google.common.truth.Truth.assertThat
import java.io.ByteArrayOutputStream
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** NAS recordings read off the NAS in large chunks, not one round trip per small read (#259). */
@UnstableApi
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class SmbReadAheadTest {

    @After
    fun finishRecording() {
        RecordingLiveState.markInactive(RECORDING_ID)
    }

    @Test
    fun `a finished recording plays through in a few large NAS reads`() {
        val file = FakeNasFile(recording(10 * MB))
        val source = SmbDataSource { file }
        val reported = mutableListOf<Int>()
        source.addTransferListener(
            object : TransferListener {
                override fun onTransferInitializing(source: DataSource, spec: DataSpec, isNetwork: Boolean) = Unit

                override fun onTransferStart(source: DataSource, spec: DataSpec, isNetwork: Boolean) = Unit

                override fun onBytesTransferred(source: DataSource, spec: DataSpec, isNetwork: Boolean, bytes: Int) {
                    reported += bytes
                }

                override fun onTransferEnd(source: DataSource, spec: DataSpec, isNetwork: Boolean) = Unit
            },
        )

        source.open(DataSpec(URI))
        val delivered = source.readAll()
        source.close()

        assertThat(delivered).isEqualTo(file.bytes)
        // Bandwidth meters see the bytes the player got, read by read, not each 512 KB fetch.
        assertThat(reported.sum()).isEqualTo(file.bytes.size)
        assertThat(reported.max()).isAtMost(EXTRACTOR_READ)
        // ~1,100 round trips if each of the extractor's 9.4 KB reads went to the NAS.
        assertThat(file.reads).isAtMost(25)
    }

    @Test
    fun `seeking plays from the new position, not what was read ahead before it`() {
        val first = FakeNasFile(recording(4 * MB))
        // A different file, so bytes kept from the first one would show up as a mismatch.
        val second = FakeNasFile(recording(4 * MB).reversedArray())
        val files = ArrayDeque(listOf(first, second))
        val source = SmbDataSource { files.removeFirst() }

        source.open(DataSpec(URI))
        source.readBytes(100 * EXTRACTOR_READ)
        source.close()
        source.open(DataSpec.Builder().setUri(URI).setPosition(SEEK_TO).build())
        val delivered = source.readBytes(20 * EXTRACTOR_READ)
        source.close()

        assertThat(delivered).isEqualTo(second.bytes.copyOfRange(SEEK_TO.toInt(), SEEK_TO.toInt() + delivered.size))
    }

    @Test
    fun `a request for part of a recording delivers exactly that part, then its end`() {
        val file = FakeNasFile(recording(4 * MB))
        val source = SmbDataSource { file }

        val opened = source.open(DataSpec.Builder().setUri(URI).setPosition(1_000).setLength(50_000).build())
        val delivered = source.readAll()
        source.close()

        assertThat(opened).isEqualTo(50_000L)
        assertThat(delivered).isEqualTo(file.bytes.copyOfRange(1_000, 51_000))
    }

    @Test
    fun `watching a NAS recording as it records plays what's appended after the edge`() {
        val nas = GrowingNas(cachesEndOfFile = false)

        val delivered = watchWhileRecording(nas)

        assertThat(delivered).isEqualTo(nas.content)
    }

    @Test
    fun `reopening the recording to see past a cached end neither repeats nor skips bytes`() {
        val nas = GrowingNas(cachesEndOfFile = true)

        val delivered = watchWhileRecording(nas)

        assertThat(nas.opens).isGreaterThan(1)
        assertThat(delivered).isEqualTo(nas.content)
    }

    private fun watchWhileRecording(nas: GrowingNas): ByteArray {
        RecordingLiveState.markActive(RECORDING_ID, "smb://nas.example.com/recordings/live.ts")
        val source = GrowingRecordingDataSource(
            ApplicationProvider.getApplicationContext(),
            openSmb = { nas.open() },
            pollMillis = 1,
        )
        source.open(DataSpec(Uri.parse("optvrec://$RECORDING_ID")))
        return source.readAll().also { source.close() }
    }

    /** Reads [count] bytes in extractor-sized reads, or fewer if the source ends first. */
    private fun DataSource.readBytes(count: Int): ByteArray {
        val out = ByteArrayOutputStream()
        val chunk = ByteArray(EXTRACTOR_READ)
        while (out.size() < count) {
            val read = read(chunk, 0, minOf(chunk.size, count - out.size()))
            if (read == C.RESULT_END_OF_INPUT) break
            out.write(chunk, 0, read)
        }
        return out.toByteArray()
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

    /** A finished recording on the NAS: answers each read from [bytes], and counts them. */
    private class FakeNasFile(val bytes: ByteArray) : SmbReader {
        var reads = 0

        override val length: Long get() = bytes.size.toLong()

        override fun readAt(buffer: ByteArray, fileOffset: Long, bufferOffset: Int, length: Int): Int {
            reads++
            val available = bytes.size - fileOffset
            if (available <= 0) return -1
            val n = minOf(length.toLong(), available).toInt()
            System.arraycopy(bytes, fileOffset.toInt(), buffer, bufferOffset, n)
            return n
        }

        override fun close() = Unit
    }

    /**
     * A recording being written to the NAS: each time a reader reaches everything there is, the
     * recorder appends its next part, and after the last part the capture finishes. A server that
     * [cachesEndOfFile] shows each open handle only what existed when it was opened.
     */
    private class GrowingNas(private val cachesEndOfFile: Boolean) {
        // The first part is shorter than a read-ahead chunk, so the first fetch comes back short.
        private val pending = ArrayDeque(
            listOf(300 * 1024, 700 * 1024, 64 * 1024, 900 * 1024).runningFold(0) { at, size -> at + size }
                .zipWithNext { from, to -> recording(to).copyOfRange(from, to) },
        )
        var content: ByteArray = pending.removeFirst()
        var opens = 0

        fun open(): SmbReader {
            opens++
            return Handle(openedAtSize = content.size)
        }

        private fun atEnd(visibleEnd: Int) {
            if (visibleEnd < content.size) return // only a fresh handle will see the rest
            val next = pending.removeFirstOrNull()
            if (next != null) content += next else RecordingLiveState.markInactive(RECORDING_ID)
        }

        private inner class Handle(private val openedAtSize: Int) : SmbReader {
            private val visibleEnd: Int get() = if (cachesEndOfFile) openedAtSize else content.size

            override val length: Long get() = visibleEnd.toLong()

            override fun readAt(buffer: ByteArray, fileOffset: Long, bufferOffset: Int, length: Int): Int {
                val end = visibleEnd
                if (fileOffset >= end) {
                    atEnd(end)
                    return -1
                }
                val n = minOf(length.toLong(), end - fileOffset).toInt()
                System.arraycopy(content, fileOffset.toInt(), buffer, bufferOffset, n)
                return n
            }

            override fun close() = Unit
        }
    }

    private companion object {
        const val RECORDING_ID = 42L

        const val MB = 1024 * 1024

        /** What Media3's TsExtractor asks for at a time: 50 transport-stream packets. */
        const val EXTRACTOR_READ = 50 * 188

        /** Inside the chunk held after reading the first file's opening 940 KB (512 KB to 1 MB). */
        const val SEEK_TO = 600_000L

        val URI: Uri = Uri.parse("smb://nas.example.com/recordings/film.ts")

        /** Bytes that differ by position, so a wrong offset shows up as a mismatch. */
        fun recording(size: Int): ByteArray = ByteArray(size) { (it * 31 + it / 251).toByte() }
    }
}
