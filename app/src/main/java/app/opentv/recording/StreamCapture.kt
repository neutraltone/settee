/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv.recording

import android.util.Log
import app.opentv.data.repo.Playable
import app.opentv.player.PlaybackErrors
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.delay
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
    private val resolve: suspend () -> Playable,
) {
    /** What a capture came to: how much it wrote, and why it wrote nothing when it did. */
    data class Outcome(val bytes: Long, val failure: String?)

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
        var refused: Playable.Failure? = null

        while (coroutineContext.isActive && !leg.ended() && deadReconnects < MAX_DEAD_RECONNECTS) {
            val bytesThisLeg = when (val playable = resolve()) {
                is Playable.Failed -> 0L.also { refused = playable.failure }
                is Playable.Ready -> leg.copy(playable)
            }
            // Nothing arrived this leg: count it, and give up only after several in a row so a
            // truly dead stream can't spin forever. A leg that delivered bytes resets the counter.
            if (bytesThisLeg > 0L) {
                deadReconnects = 0
            } else if (++deadReconnects < MAX_DEAD_RECONNECTS) {
                delay(RECONNECT_BACKOFF_MILLIS)
            }
        }
        val failure = refused?.takeIf { bytes == 0L }?.let(PlaybackErrors::describe)
        return Outcome(bytes, failure)
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

        fun ended() = endAtMillis > 0 && clock() >= endAtMillis

        /** Appends what [playable] serves until it closes, the end or a stop; returns how much. */
        suspend fun copy(playable: Playable.Ready): Long {
            val request = Request.Builder().url(playable.url).header("User-Agent", playable.userAgent).build()
            val call = http.newCall(request).also(onCall)
            return try {
                call.execute().use { response ->
                    if (!response.isSuccessful) throw IOException("HTTP ${response.code}")
                    copy(response.body.byteStream())
                }
            } catch (t: IOException) {
                // A dropped connection isn't a failure — reconnect and carry on.
                Log.i(TAG, "Recording connection dropped (${t.message}); reconnecting")
                0L
            }
        }

        private suspend fun copy(input: InputStream): Long {
            var leg = 0L
            var read = 0
            // What has arrived is written before the end is checked, so the last read isn't lost.
            while (coroutineContext.isActive && input.read(buffer).also { read = it } >= 0) {
                output.write(buffer, 0, read)
                bytes += read
                leg += read
                if (bytes - lastReport >= SIZE_REPORT_BYTES) {
                    lastReport = bytes
                    onProgress(bytes)
                }
                if (ended()) break
            }
            return leg
        }
    }

    private companion object {
        const val TAG = "OpenTV"
        const val BUFFER_BYTES = 64 * 1024
        const val SIZE_REPORT_BYTES = 4L * 1024 * 1024

        // Providers close a live .ts socket every ~30s; reconnect and keep appending. Give up only
        // after this many consecutive reconnects deliver nothing (the stream is genuinely gone).
        const val MAX_DEAD_RECONNECTS = 5
        const val RECONNECT_BACKOFF_MILLIS = 1500L
    }
}
