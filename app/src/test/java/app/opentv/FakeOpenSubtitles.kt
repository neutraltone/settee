/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv

import java.util.Collections
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer

/**
 * OpenSubtitles, answering `"<METHOD> <path after /api/v1>"` (or a download link's path) with what
 * [replies] holds. A reply of `"!"` fails as an unreachable server; `"!406 {…}"` answers that
 * status with that body. Unknown requests get an empty result.
 */
class FakeOpenSubtitles {
    val replies = mutableMapOf<String, String>()
    val requests: MutableList<Request> = Collections.synchronizedList(mutableListOf())

    /** Every request body sent, in order. */
    val bodies: MutableList<String> = Collections.synchronizedList(mutableListOf())

    /** Held open, downloads wait until it counts down, so a test can see what shows while one is fetched. */
    @Volatile var downloadsHeld: java.util.concurrent.CountDownLatch? = null

    val http: OkHttpClient = OkHttpClient.Builder().addInterceptor { chain ->
        val request = chain.request()
        requests += request
        request.body?.let { body -> bodies += Buffer().also(body::writeTo).readUtf8() }
        val path = request.url.encodedPath.removePrefix("/api/v1")
        // Bounded, so a test that fails before letting go fails rather than hangs.
        if (path == "/download") downloadsHeld?.await(10, java.util.concurrent.TimeUnit.SECONDS)
        val reply = replies["${request.method} $path"] ?: if (path == "/subtitles") """{"data": []}""" else "{}"
        if (reply == "!") throw java.io.IOException("OpenSubtitles timed out")
        val status = Regex("!(\\d{3})\\s*(.*)", RegexOption.DOT_MATCHES_ALL).matchEntire(reply)
        Response.Builder()
            .request(request)
            .protocol(Protocol.HTTP_1_1)
            .code(status?.groupValues?.get(1)?.toInt() ?: 200)
            .message("OK")
            .body((status?.groupValues?.get(2) ?: reply).toResponseBody("application/json".toMediaType()))
            .build()
    }.build()
}
