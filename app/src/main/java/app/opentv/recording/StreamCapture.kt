/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv.recording

import android.util.Log
import app.opentv.data.repo.Playable
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import okhttp3.Call
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * Copies a live stream into a recording's file until its end, reconnecting as the provider drops it.
 *
 * Real IPTV `.ts` live endpoints commonly serve a rolling ~30s window and then close the socket,
 * expecting the client to re-request (ExoPlayer does this on its own during playback). A single
 * read-to-EOF therefore captures only ~30s. So when the connection drops this reopens it and keeps
 * appending, until the end, a stop, or the stream is genuinely gone (several empty reconnects).
 *
 * Every reconnect asks [resolve] for the link again rather than reusing the first: a Stalker link
 * is short-lived, and one minted when the capture started has expired by a later reconnect.
 */
class StreamCapture(
    private val http: OkHttpClient,
    private val clock: () -> Long = System::currentTimeMillis,
    private val reconnectAfterMillis: Long = RECONNECT_BACKOFF_MILLIS,
    private val resolve: suspend () -> Playable,
) {
    /** What a capture came to: how much it wrote, and why it stopped. */
    data class Outcome(val bytes: Long, val ending: Ending)

    /** Why a capture stopped by itself. A stop by hand is a cancellation, and has no [Outcome]. */
    sealed interface Ending {
        /** Its end came. */
        data object Finished : Ending

        /** The storage it was writing to said it was full. */
        data object StorageFull : Ending

        /** Writing failed some other way, as a drive pulled out or a NAS gone quiet does: [cause]. */
        data class StorageLost(val cause: Throwable) : Ending

        /** The provider answered with HTTP [status], and the capture gave up asking. */
        data class Refused(val status: Int) : Ending

        /** The provider answered, and then sent nothing, again and again: the channel stopped. */
        data object StreamStopped : Ending

        /** There was no link to connect to, for [failure]; [cause] is [Playable.Failed]'s. */
        data class NoLink(val failure: Playable.Failure, val cause: Throwable? = null) : Ending
    }

    /** Written so far; read by a capture that was stopped part-way, which never gets its [Outcome]. */
    @Volatile
    var bytes = 0L
        private set

    /**
     * Captures into [output] until [endAtMillis] (0 for no end). [onCall] is handed each connection
     * so a stop can cancel it mid-read; [onProgress] hears the size every few megabytes.
     */
    suspend fun run(
        output: OutputStream,
        endAtMillis: Long,
        onCall: (Call) -> Unit = {},
        onProgress: suspend (Long) -> Unit = {},
    ): Outcome {
        val leg = Leg(output, endAtMillis, onCall, onProgress)
        var deadReconnects = 0
        // Why the legs since video last arrived got none, when the provider said: what the capture
        // ends with if it gives up.
        var refused: Ending? = null

        while (coroutineContext.isActive && !leg.ended() && deadReconnects < MAX_DEAD_RECONNECTS) {
            val bytesThisLeg = try {
                leg.connect()
            } catch (failed: StorageFailed) {
                // Reconnecting can't help: the stream is fine, it's where it goes that isn't.
                return Outcome(bytes, failed.ending)
            }
            leg.refusal?.let { refused = it }
            // Nothing arrived this leg: count it, and give up only after several in a row so a
            // truly dead stream can't spin forever. A leg that delivered bytes resets the counter.
            if (bytesThisLeg > 0L) {
                deadReconnects = 0
                refused = null
            } else if (++deadReconnects < MAX_DEAD_RECONNECTS) {
                delay(reconnectAfterMillis)
            }
        }
        // A stop by hand isn't the stream stopping: it has no outcome, whatever the last leg got.
        coroutineContext.ensureActive()
        // Its end coming with nothing written is a stream that stopped too: why nothing came is the reason.
        val stopped = deadReconnects >= MAX_DEAD_RECONNECTS || bytes == 0L
        return Outcome(bytes, if (stopped) refused ?: Ending.StreamStopped else Ending.Finished)
    }

    /** One connection's worth of the capture: what it writes into, and until when. */
    private inner class Leg(
        private val output: OutputStream,
        private val endAtMillis: Long,
        private val onCall: (Call) -> Unit,
        private val onProgress: suspend (Long) -> Unit,
    ) {
        private val buffer = ByteArray(BUFFER_BYTES)
        private var lastReport = 0L

        /** Why the last connection got nothing, when the provider said: no link, or a refusal. */
        var refusal: Ending? = null
            private set

        fun ended() = endAtMillis > 0 && clock() >= endAtMillis

        /** Asks for the link again and appends what it serves until it closes, the end or a stop; returns how much. */
        suspend fun connect(): Long {
            refusal = null
            return when (val playable = resolve()) {
                is Playable.Failed -> 0L.also { refusal = Ending.NoLink(playable.failure, playable.cause) }
                is Playable.Ready -> copy(playable)
            }
        }

        private suspend fun copy(playable: Playable.Ready): Long {
            val request = Request.Builder().url(playable.url).header("User-Agent", playable.userAgent).build()
            val call = http.newCall(request).also(onCall)
            val before = bytes
            try {
                call.execute().use { response ->
                    if (!response.isSuccessful) {
                        refusal = Ending.Refused(response.code)
                        throw IOException("HTTP ${response.code}")
                    }
                    copy(response.body.byteStream())
                }
            } catch (t: IOException) {
                // A dropped connection isn't a failure — reconnect and carry on. One that sent video
                // first was working, however it ended: counting it as dead gave up on a stream
                // whose provider resets it as it goes.
                Log.i(TAG, "Recording connection dropped (${t.message}); reconnecting")
            }
            return bytes - before
        }

        private suspend fun copy(input: InputStream) {
            var read = 0
            // What has arrived is written before the end is checked, so the last read isn't lost.
            while (coroutineContext.isActive && input.read(buffer).also { read = it } >= 0) {
                write(read)
                bytes += read
                if (bytes - lastReport >= SIZE_REPORT_BYTES) {
                    lastReport = bytes
                    onProgress(bytes)
                }
                if (ended()) break
            }
        }

        /**
         * Writes what was just read, telling a failure here apart from one reading the stream: not an
         * [IOException], so [copy] doesn't take it for a dropped connection.
         */
        @Suppress("TooGenericExceptionCaught") // An SMB share fails a write with a RuntimeException.
        private fun write(length: Int) {
            try {
                output.write(buffer, 0, length)
            } catch (e: Exception) {
                throw StorageFailed(if (saysFull(e)) Ending.StorageFull else Ending.StorageLost(e), e)
            }
        }
    }

    /**
     * Whether [error], or anything it wraps, is storage saying it's out of room: Android's files and
     * a USB drive say ENOSPC, an SMB share STATUS_DISK_FULL. Read from the message, as neither has a
     * type of its own, and nothing but this decides what the viewer is told.
     */
    private fun saysFull(error: Throwable): Boolean = generateSequence(error) { it.cause }
        .take(MAX_CAUSE_DEPTH)
        .any { cause -> FULL.any { cause.message?.contains(it) == true } }

    /** Writing to the recording's storage failed, as [ending] says. */
    private class StorageFailed(val ending: Ending, cause: Throwable) : Exception(cause)

    companion object {
        private const val TAG = "OpenTV"
        private const val BUFFER_BYTES = 64 * 1024
        private const val SIZE_REPORT_BYTES = 4L * 1024 * 1024

        // Providers close a live .ts socket every ~30s; reconnect and keep appending. Give up only
        // after this many consecutive reconnects deliver nothing (the stream is genuinely gone).
        private const val MAX_DEAD_RECONNECTS = 5
        const val RECONNECT_BACKOFF_MILLIS = 1500L

        private val FULL = listOf("ENOSPC", "STATUS_DISK_FULL")

        /** Far deeper than anything wraps a write's failure, and stops a cause that loops back on itself. */
        private const val MAX_CAUSE_DEPTH = 10
    }
}
