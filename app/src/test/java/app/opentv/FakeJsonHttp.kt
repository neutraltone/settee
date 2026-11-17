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

/**
 * A network that answers every request with JSON from [reply]: a body, or `"!"` to fail the request as a
 * dropped connection would, or `"404"` / `"!503"` (any status) to answer with that status instead.
 */
class FakeJsonHttp(private val reply: (Request) -> String) {
    /** Every request made, in order. */
    val asked: MutableList<Request> = Collections.synchronizedList(mutableListOf())

    /** The hosts asked, in order. */
    fun hosts(): List<String> = synchronized(asked) { asked.map { it.url.host } }

    val http: OkHttpClient = OkHttpClient.Builder().addInterceptor { chain ->
        val request = chain.request().also { asked += it }
        val body = reply(request)
        if (body == "!") throw java.io.IOException("Connection dropped")
        val status = Regex("!?(\\d{3})").matchEntire(body)?.groupValues?.get(1)?.toInt() ?: 200
        Response.Builder()
            .request(request)
            .protocol(Protocol.HTTP_1_1)
            .code(status)
            .message("Answer")
            .body((if (status == 200) body else "{}").toResponseBody("application/json".toMediaType()))
            .build()
    }.build()
}
