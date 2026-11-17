/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv.data.remote

import app.opentv.data.model.EpisodeTimings
import java.io.IOException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.jsonObject
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * IntroDB (introdb.app): community-timed intros, recaps and credits, by the series' IMDb id (#517).
 * Read with no key; its data is open under the ODbL, which asks for attribution (Settings → About).
 * Asked only when [TheIntroDbClient] has no credits for an episode: on the household's episodes
 * TheIntroDB matched as well or better, and once by three minutes.
 *
 * Blocking, like [TmdbClient]: the caller is already off the main thread.
 */
class IntroDbClient(private val http: OkHttpClient) {

    /**
     * The timings of [season] episode [episode] of the show IMDb knows as [imdbId]; null in a success
     * when IntroDB has nothing timed or confirmed for it, and a failure when it couldn't be asked.
     */
    fun timings(imdbId: String, season: Int, episode: Int): Result<EpisodeTimings?> = runCatching {
        val url = BASE.newBuilder()
            .addQueryParameter("imdb_id", imdbId)
            .addQueryParameter("season", season.toString())
            .addQueryParameter("episode", episode.toString())
            .build()
        val answer = fetch(url) ?: return@runCatching null
        val intro = answer["intro"].objectOrNull()
        val recap = answer["recap"].objectOrNull()
        val outro = answer["outro"].objectOrNull()
        val confirmed = listOf("no_intro", "no_recap", "no_outro").any { answer.flag(it) }
        if (listOfNotNull(intro, recap, outro).isEmpty() && !confirmed) return@runCatching null
        EpisodeTimings(
            show = "imdb:$imdbId",
            season = season,
            episode = episode,
            introStartMillis = intro?.let { it.millis("start_ms") ?: 0 },
            introEndMillis = intro?.millis("end_ms"),
            recapStartMillis = recap?.let { it.millis("start_ms") ?: 0 },
            recapEndMillis = recap?.millis("end_ms"),
            creditsStartMillis = outro?.millis("start_ms"),
            creditsEndMillis = outro?.millis("end_ms"),
            sceneAfterCredits = answer["post_credits"].objectOrNull() != null,
            noIntro = answer.flag("no_intro"),
            noRecap = answer.flag("no_recap"),
            noCredits = answer.flag("no_outro"),
            source = EpisodeTimings.INTRO_DB,
        )
    }

    private fun fetch(url: HttpUrl): JsonObject? {
        val request = Request.Builder().url(url).header("User-Agent", "Settee").build()
        return http.newCall(request).execute().use { response ->
            when {
                response.code == HTTP_NOT_FOUND -> null
                !response.isSuccessful -> throw IOException("IntroDB answered ${response.code}")
                else -> Json.parseToJsonElement(response.body.string()).jsonObject
            }
        }
    }

    private fun JsonObject.flag(name: String): Boolean =
        (this[name] as? kotlinx.serialization.json.JsonPrimitive)?.booleanOrNull == true

    private companion object {
        val BASE: HttpUrl = "https://api.introdb.app/segments".toHttpUrl()
        const val HTTP_NOT_FOUND = 404
    }
}
