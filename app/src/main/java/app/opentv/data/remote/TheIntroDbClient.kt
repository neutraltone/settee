/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv.data.remote

import app.opentv.data.model.EpisodeTimings
import java.io.IOException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.longOrNull
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * TheIntroDB (theintrodb.org): community-timed intros, recaps and credits, by TMDB id (#517). Read
 * with no key or account. Its terms allow free, non-commercial apps that ask it from the viewer's own
 * device, which is how Settee asks, and storing one viewer's own episodes' timings.
 *
 * Blocking, like [TmdbClient]: the caller is already off the main thread.
 */
class TheIntroDbClient(private val http: OkHttpClient) {

    /**
     * The timings of [season] episode [episode] of the show TMDB knows as [tmdbId]; null in a success
     * when TheIntroDB doesn't have it, and a failure when it couldn't be asked.
     */
    fun timings(tmdbId: String, season: Int, episode: Int): Result<EpisodeTimings?> = runCatching {
        // Not the stream's length, which it takes to choose between cuts: given it, it chose a wrong
        // one for an episode the household measured, where without it the time was right.
        val url = BASE.newBuilder()
            .addQueryParameter("tmdb_id", tmdbId)
            .addQueryParameter("season", season.toString())
            .addQueryParameter("episode", episode.toString())
            .build()
        val answer = fetch(url) ?: return@runCatching null
        val credits = answer.segments("credits")
        val intro = answer.segments("intro").firstOrNull()?.takeIf { it.lengthMillis > 0 }
        val recap = answer.segments("recap").firstOrNull()?.takeIf { it.lengthMillis > 0 }
        if (credits.isEmpty() && intro == null && recap == null) return@runCatching null
        EpisodeTimings(
            show = "tmdb:$tmdbId",
            season = season,
            episode = episode,
            introStartMillis = intro?.startMillis,
            introEndMillis = intro?.endMillis,
            recapStartMillis = recap?.startMillis,
            recapEndMillis = recap?.endMillis,
            creditsStartMillis = credits.firstOrNull()?.startMillis,
            creditsEndMillis = credits.firstOrNull()?.endMillis,
            // Credits timed in two parts have something between them that isn't credits.
            sceneAfterCredits = credits.size > 1,
            source = EpisodeTimings.THE_INTRO_DB,
        )
    }

    /** The answer to [url]; null when TheIntroDB doesn't have the episode. Throws when it didn't answer. */
    private fun fetch(url: HttpUrl): JsonObject? {
        val request = Request.Builder().url(url).header("User-Agent", "Settee").build()
        return http.newCall(request).execute().use { response ->
            when {
                response.code == HTTP_NOT_FOUND -> null
                !response.isSuccessful -> throw IOException("TheIntroDB answered ${response.code}")
                // A page in front of the API (a rate limit, a challenge) isn't JSON, and fails here.
                else -> Json.parseToJsonElement(response.body.string()).jsonObject
            }
        }
    }

    /** One timed stretch; a start TheIntroDB leaves null is the very beginning, an end left null is the end. */
    private data class Segment(val startMillis: Long, val endMillis: Long?) {
        val lengthMillis: Long get() = endMillis?.let { it - startMillis } ?: Long.MAX_VALUE
    }

    private fun JsonObject.segments(name: String): List<Segment> =
        (this[name] as? JsonArray).orEmpty().mapNotNull { element ->
            val o = element as? JsonObject ?: return@mapNotNull null
            Segment(startMillis = o.millis("start_ms") ?: 0, endMillis = o.millis("end_ms"))
        }

    private companion object {
        val BASE: HttpUrl = "https://api.theintrodb.org/v3/media".toHttpUrl()
        const val HTTP_NOT_FOUND = 404
    }
}

/** A millisecond field, which may be missing, null, or (from some panels' habits) a string. */
internal fun JsonObject.millis(name: String): Long? = (this[name] as? kotlinx.serialization.json.JsonPrimitive)?.let {
    it.longOrNull
        ?: it.content.toDoubleOrNull()?.toLong()
}

/** [this] as an object, or null when it's JSON null or anything else. */
internal fun JsonElement?.objectOrNull(): JsonObject? = this as? JsonObject
