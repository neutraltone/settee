/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv.player

import android.content.Context
import android.net.Uri
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.BaseDataSource
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import app.opentv.core.ServiceLocator
import app.opentv.recording.RecordingLiveState
import app.opentv.recording.RecordingStorage
import app.opentv.recording.SmbClient
import app.opentv.recording.SmbReader
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import kotlinx.coroutines.runBlocking

/**
 * Plays a recording *while it is still being recorded*, whether it's being written to this box's
 * internal storage or straight to a NAS over SMB.
 *
 * A normal file/SMB source reads to the current end and reports end-of-stream — which for an
 * in-progress recording would stop a few seconds in, at whatever had been written when the player
 * opened it. This source tail-follows instead: when it reaches the end of what's on disk it asks
 * [RecordingLiveState] whether the capture is still running, and if so waits for the next bytes.
 * Only when the capture is genuinely done does it report the real end.
 *
 * That means zero extra load on the provider — the bytes are already being pulled by the recorder;
 * the player just reads the same growing file — which is the whole point on a single-connection
 * account, where a second live stream would get the first one cut off.
 *
 * Addressed by `optvrec://<recordingId>`. Internal recordings tail a local file; NAS recordings tail
 * the SMB file (asking the open handle for its end of file, and reopening it only after a long stall
 * in case the server caches the end of file on an open handle).
 */
@OptIn(UnstableApi::class)
class GrowingRecordingDataSource internal constructor(
    private val context: Context,
    private val openSmb: (locator: String) -> SmbReader,
    private val pollMillis: Long = POLL_MILLIS,
    private val sleep: (millis: Long) -> Unit = Thread::sleep,
) : BaseDataSource(true) {

    constructor(context: Context) : this(
        context,
        { SmbClient.openForRead(RecordingStorage.smbConfig(ServiceLocator.get(context).settings), it) },
    )

    private var uri: Uri? = null
    private var recordingId: Long = -1L
    private var position: Long = 0L
    private var opened: Boolean = false

    @Volatile
    private var closed: Boolean = false

    // Exactly one of these is used, decided at open() by the recording's locator.
    private var file: RandomAccessFile? = null
    private var smb: SmbReader? = null
    private var smbLocator: String = ""
    private val readAhead = SmbReadAhead()

    // Polls at the SMB growing edge since bytes last arrived; the handle is reopened once it reaches
    // REOPEN_AFTER_POLLS. Kept across reads so a server whose answers never move still gets reopened.
    private var stalledPolls = 0

    override fun open(dataSpec: DataSpec): Long {
        transferInitializing(dataSpec)
        uri = dataSpec.uri
        closed = false
        recordingId = dataSpec.uri.host?.toLongOrNull()
            ?: dataSpec.uri.schemeSpecificPart?.trim('/')?.toLongOrNull()
            ?: throw IOException("Bad recording URI: ${dataSpec.uri}")

        // Prefer the live registry (no I/O); fall back to the row if it just finished.
        val locator = RecordingLiveState.pathOf(recordingId)
            ?: runBlocking { ServiceLocator.get(context).recordingRepository.byId(recordingId)?.filePath }
            ?: throw IOException("Recording $recordingId not found")

        position = dataSpec.position

        if (locator.startsWith("smb://", ignoreCase = true)) {
            smbLocator = locator
            smb = openSmbWithGrace().also { readAhead.reader = it }
        } else {
            // Internal: the auto-switch can open this the instant capture starts, a moment before
            // the recorder has created the file. Give it a short grace to appear.
            val target = File(locator)
            var waited = 0L
            while (!target.exists() && waited < OPEN_WAIT_MILLIS) {
                if (closed) throw IOException("closed while waiting for recording $recordingId")
                sleep(pollMillis)
                waited += pollMillis
            }
            if (!target.exists()) throw IOException("Recording $recordingId file not ready: $locator")
            val f = RandomAccessFile(target, "r")
            if (position > 0) f.seek(position)
            file = f
        }

        opened = true
        transferStarted(dataSpec)
        // Growing file: length is not known up front.
        return C.LENGTH_UNSET.toLong()
    }

    /** Open the SMB file for reading, tolerating the brief window before the recorder has created it. */
    private fun openSmbWithGrace(): SmbReader {
        var waited = 0L
        while (waited < OPEN_WAIT_MILLIS) {
            if (closed) throw IOException("closed while opening $smbLocator")
            val handle = runCatching { openSmb(smbLocator) }.getOrNull()
            if (handle != null) return handle
            sleep(pollMillis)
            waited += pollMillis
        }
        throw IOException("NAS recording $recordingId not ready: $smbLocator")
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (length == 0) return 0
        // By locator, not by handle: a reopen that failed leaves no handle, and is retried.
        return if (smbLocator.isNotEmpty()) readSmb(buffer, offset, length) else readLocal(buffer, offset, length)
    }

    private fun readLocal(buffer: ByteArray, offset: Int, length: Int): Int {
        val f = file ?: return C.RESULT_END_OF_INPUT
        while (!closed) {
            val available = f.length() - position
            if (available > 0) {
                val toRead = minOf(length.toLong(), available).toInt()
                val read = f.read(buffer, offset, toRead)
                if (read > 0) {
                    position += read
                    bytesTransferred(read)
                    return read
                }
            }
            if (!RecordingLiveState.isActive(recordingId)) {
                if (f.length() - position > 0) continue
                return C.RESULT_END_OF_INPUT
            }
            if (pollOnce()) return C.RESULT_END_OF_INPUT
        }
        return C.RESULT_END_OF_INPUT
    }

    private fun readSmb(buffer: ByteArray, offset: Int, length: Int): Int {
        while (!closed) {
            // A short chunk here is the growing edge, not the end: its bytes are served, and the
            // next miss fetches again from wherever they ran out.
            val read = runCatching { readAhead.read(position, buffer, offset, length) }.getOrDefault(-1)
            if (read > 0) {
                position += read
                stalledPolls = 0
                bytesTransferred(read)
                return read
            }
            // At the growing edge. If the capture is done, this really is the end.
            if (!RecordingLiveState.isActive(recordingId)) {
                val last = runCatching { readAhead.read(position, buffer, offset, length) }.getOrDefault(-1)
                if (last > 0) {
                    position += last
                    bytesTransferred(last)
                    return last
                }
                return C.RESULT_END_OF_INPUT
            }
            if (!awaitSmbGrowth()) return C.RESULT_END_OF_INPUT
        }
        return C.RESULT_END_OF_INPUT
    }

    /**
     * Waits at the growing edge until there may be more to read: the open handle's end of file has
     * passed [position], the capture has finished, or the handle was reopened. False if closed or
     * interrupted.
     *
     * The recorder's buffered stream lands about a megabyte at a time, seconds apart, so asking the
     * open handle is what keeps up. Reconnecting (a new client, login and share) on each of those
     * waits cost a connection every step, and one that failed ended playback mid-recording.
     */
    private fun awaitSmbGrowth(): Boolean {
        while (!closed) {
            if (pollOnce()) return false
            if (!RecordingLiveState.isActive(recordingId)) return true
            // Some servers cache the end of file on an open handle, so neither asking nor reading
            // through it sees what's been appended: after a long stall, reopen it to refresh it.
            // Counted whatever the handle answers, in case its answer moves while its reads don't.
            // While the capture runs a reopen that fails is tried again later, not taken as the end.
            if (++stalledPolls >= REOPEN_AFTER_POLLS) {
                stalledPolls = 0
                runCatching { smb?.close() }
                smb = runCatching { openSmb(smbLocator) }.getOrNull()
                readAhead.reader = smb
                if (smb != null) return true
                continue
            }
            val end = smb?.let { runCatching { it.endOfFile() }.getOrNull() }
            if (end != null && end > position) return true
        }
        return false
    }

    /** Sleep one poll; returns true if interrupted (caller should end). */
    private fun pollOnce(): Boolean = try {
        sleep(pollMillis)
        false
    } catch (e: InterruptedException) {
        Thread.currentThread().interrupt()
        true
    }

    override fun getUri(): Uri? = uri

    override fun close() {
        closed = true
        runCatching { file?.close() }
        runCatching { smb?.close() }
        file = null
        smb = null
        smbLocator = ""
        stalledPolls = 0
        readAhead.reader = null
        if (opened) {
            opened = false
            transferEnded()
        }
        uri = null
    }

    class Factory(private val context: Context) : DataSource.Factory {
        override fun createDataSource(): DataSource = GrowingRecordingDataSource(context)
    }

    private companion object {
        /** How long to wait between checks for freshly-written bytes. Small so close() is prompt. */
        const val POLL_MILLIS = 250L

        /** How long open() waits for the capture to create the file before giving up. */
        const val OPEN_WAIT_MILLIS = 8_000L

        /**
         * Reopen an SMB read handle after this many polls without new bytes (~10 s), to refresh a
         * server's cached end of file. Longer than the recorder takes to land its next megabyte.
         */
        const val REOPEN_AFTER_POLLS = 40
    }
}
