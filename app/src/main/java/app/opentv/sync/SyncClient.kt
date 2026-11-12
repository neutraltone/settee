/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv.sync

import app.opentv.core.HttpStatus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * Pulls a watch-history bundle from another OpenTV that is currently sharing on the LAN.
 *
 * Given the address the other device shows ("192.168.0.14:41234") and its six-digit code, this
 * does one plain HTTP GET over the local network and parses the JSON. No retries, no discovery —
 * the sharing device is up for a few minutes and the user typed exactly where it is.
 */
class SyncClient(private val httpClient: OkHttpClient) {

    /** The other device answered [status]: 401 when the code didn't match. */
    class Refused(override val status: Int) :
        java.io.IOException("The other device answered HTTP $status"),
        HttpStatus

    private val json = Json { ignoreUnknownKeys = true }

    suspend fun pull(address: String, code: String): Result<SyncBundle> = withContext(Dispatchers.IO) {
        runCatching {
            val cleanAddress = address.trim().removePrefix("http://").removePrefix("https://").trimEnd('/')
            val cleanCode = code.trim().filter { it.isDigit() }
            val url = "http://$cleanAddress/export?c=$cleanCode"
            val request = Request.Builder().url(url).get().build()
            httpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) throw Refused(response.code)
                val body = response.body.string()
                json.decodeFromString(SyncBundle.serializer(), body)
            }
        }
    }
}
