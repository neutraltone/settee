/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv

import app.opentv.data.model.Source
import app.opentv.data.model.SourceKind
import com.google.common.truth.Truth.assertThat
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody

/** Runs [block] off the test's virtual clock, with the panel's real threads, once [hold] is reached. */
suspend fun <T> during(hold: FakePanel.Hold, block: suspend () -> T): T = withContext(Dispatchers.Default) {
    assertThat(hold.reached.await(10, TimeUnit.SECONDS)).isTrue()
    block()
}

/** An Xtream source at a placeholder address, for [FakePanel] to answer. */
val PANEL_SOURCE =
    Source(id = 1, name = "Test", kind = SourceKind.XTREAM, url = "http://example.com", username = "u", password = "p")

/**
 * An Xtream panel that answers `player_api.php?action=…` with whatever JSON [replies] holds for
 * that action (`get_series_info` and `get_vod_info` can be keyed by title too, as
 * `get_series_info:<id>`), and `[]` otherwise. A reply of `"!"` fails the request, as a panel timing
 * out would.
 */
class FakePanel(vararg initial: Pair<String, String>) {
    val replies = initial.toMap().toMutableMap()

    /** Every `action` (with its series or movie id, where it has one) asked for, in order. */
    val asked: MutableList<String> = java.util.Collections.synchronizedList(mutableListOf())

    /** Every Stalker `cmd` a link was asked for, in order. */
    val cmds = mutableListOf<String>()

    private val holds = java.util.concurrent.ConcurrentHashMap<String, Hold>()

    /** Keeps requests for [action] waiting until [Hold.release], so a test can act during a download. */
    fun hold(action: String) = Hold().also { holds[action] = it }

    class Hold {
        val reached = java.util.concurrent.CountDownLatch(1)
        private val gate = java.util.concurrent.CountDownLatch(1)

        fun release() = gate.countDown()

        internal fun pass() {
            reached.countDown()
            // Bounded, so a test that fails before releasing this fails rather than hangs.
            gate.await(10, java.util.concurrent.TimeUnit.SECONDS)
        }
    }

    val http: OkHttpClient = OkHttpClient.Builder().addInterceptor { chain ->
        val request = chain.request()
        // A provider's guide has no action, so it answers to its file name instead.
        val guide = request.url.encodedPath.endsWith("xmltv.php")
        // TMDB shares the client, so it answers to its path, keyed by what was searched for.
        val tmdb = request.url.host == "api.themoviedb.org"
        val action = when {
            guide -> "xmltv.php"
            tmdb -> request.url.encodedPath
            else -> request.url.queryParameter("action").orEmpty()
        }
        val keyed = listOf("series_id", "vod_id", "query")
            .firstNotNullOfOrNull { request.url.queryParameter(it) }
            ?.let { "$action:$it" }
        asked += keyed ?: action
        if (action == "create_link") cmds += request.url.queryParameter("cmd").orEmpty()
        val body = keyed?.let { replies[it] } ?: replies[action] ?: "[]"
        (keyed?.let { holds[it] } ?: holds[action])?.pass()
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
