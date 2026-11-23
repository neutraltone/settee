/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv.recording

import java.io.IOException
import java.io.OutputStream
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody
import okhttp3.ResponseBody.Companion.toResponseBody

// What a capture is tested against: a provider's answers, and storage that fills up.

/** A provider's answer to [request]: HTTP [code], with [body]. */
fun answer(request: Request, code: Int, body: ResponseBody = "".toResponseBody()): Response = Response.Builder()
    .request(request)
    .protocol(Protocol.HTTP_1_1)
    .code(code)
    .message("Status $code")
    .body(body)
    .build()

/** A provider sending [text] as the video, and closing the connection, as live .ts endpoints do. */
fun video(request: Request, text: String): Response =
    answer(request, 200, text.toResponseBody("video/mp2t".toMediaType()))

/** Storage with room for [capacity] bytes, failing a write past that as Android's own files do. */
class FullAfter(private val capacity: Int) : OutputStream() {
    private var written = 0

    override fun write(b: Int) = write(byteArrayOf(b.toByte()), 0, 1)

    override fun write(b: ByteArray, off: Int, len: Int) {
        if (written + len > capacity) throw IOException("write failed: ENOSPC (No space left on device)")
        written += len
    }
}
