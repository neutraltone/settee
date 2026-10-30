/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv

import app.opentv.data.model.Source
import app.opentv.data.model.SourceKind
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody

/** An Xtream source at a placeholder address, for [FakePanel] to answer. */
val PANEL_SOURCE =
    Source(id = 1, name = "Test", kind = SourceKind.XTREAM, url = "http://example.com", username = "u", password = "p")

/**
 * An Xtream panel that answers `player_api.php?action=…` with whatever JSON [replies] holds for
 * that action (`get_series_info` can be keyed by series too, as `get_series_info:<id>`), and `[]`
 * otherwise. A reply of `"!"` fails the request, as a panel timing out would.
 */
class FakePanel(vararg initial: Pair<String, String>) {
    val replies = initial.toMap().toMutableMap()

    /** Every `action` (with its series id, where it has one) asked for, in order. */
    val asked = mutableListOf<String>()

    /** Every Stalker `cmd` a link was asked for, in order. */
    val cmds = mutableListOf<String>()

    val http: OkHttpClient = OkHttpClient.Builder().addInterceptor { chain ->
        val request = chain.request()
        val action = request.url.queryParameter("action").orEmpty()
        val keyed = request.url.queryParameter("series_id")?.let { "$action:$it" }
        asked += keyed ?: action
        if (action == "create_link") cmds += request.url.queryParameter("cmd").orEmpty()
        val body = keyed?.let { replies[it] } ?: replies[action] ?: "[]"
        if (body == "!") throw java.io.IOException("Panel timed out")
        Response.Builder()
            .request(request)
            .protocol(Protocol.HTTP_1_1)
            .code(200)
            .message("OK")
            .body(body.toResponseBody("application/json".toMediaType()))
            .build()
    }.build()
}

/** A Stalker portal at a placeholder address and MAC, for [FakePanel] to answer (`handshake`, `create_link`). */
val STALKER_SOURCE =
    Source(
        id = 2,
        name = "Portal",
        kind = SourceKind.STALKER,
        url = "http://example.com",
        macAddress = "00:1A:79:00:00:01",
    )
