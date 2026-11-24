/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv.data.remote

import app.opentv.core.ServiceFailure
import app.opentv.core.ServiceFailure.Operation
import app.opentv.core.ServiceFailure.Service
import java.io.IOException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.longOrNull
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request

// What TheIntroDbClient and IntroDbClient share: both answer an episode as one JSON object.

/**
 * [url]'s JSON answer from the database called [database]; null for a 404, its way of saying it doesn't
 * have the episode, so never reported. Throws when it didn't answer, or answered with a page in front
 * of the API (a rate limit, a challenge), which isn't JSON; [reportFailure] hears the failures that
 * are Settee's own (ADR 0010).
 */
internal fun OkHttpClient.episodeJson(
    url: HttpUrl,
    database: String,
    reportFailure: (ServiceFailure) -> Unit,
): JsonObject? {
    val request = Request.Builder().url(url).header("User-Agent", "Settee").build()
    return newCall(request).execute().use { response ->
        when {
            response.code == HTTP_NOT_FOUND -> null
            !response.isSuccessful -> {
                ServiceFailure.ofStatus(Service.INTRODB, Operation.TIMINGS, response.code)?.let(reportFailure)
                throw IOException("$database answered ${response.code}")
            }
            else -> try {
                Json.parseToJsonElement(response.body.string()).jsonObject
            } catch (unreadable: IllegalArgumentException) {
                // SerializationException is one: not JSON, or JSON that isn't an object.
                reportFailure(ServiceFailure.unreadable(Service.INTRODB, Operation.TIMINGS))
                throw unreadable
            }
        }
    }
}

/** A millisecond field; null when it's missing or null, as a start at the very beginning can be. */
internal fun JsonObject.millis(name: String): Long? =
    (this[name] as? JsonPrimitive)?.let { it.longOrNull ?: it.content.toDoubleOrNull()?.toLong() }

/** A true-or-false field; false when it's missing. */
internal fun JsonObject.flag(name: String): Boolean = (this[name] as? JsonPrimitive)?.booleanOrNull == true

/** [this] as an object, or null when it's JSON null or anything else. */
internal fun JsonElement?.objectOrNull(): JsonObject? = this as? JsonObject

private const val HTTP_NOT_FOUND = 404
