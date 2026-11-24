/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv.data.remote

import app.opentv.core.ServiceFailure
import app.opentv.data.model.EpisodeTimings
import app.opentv.data.model.TimingsSource
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient

/**
 * TheIntroDB (theintrodb.org): community-timed intros, recaps and credits, by TMDB id (#517). Read
 * with no key or account. Its terms allow free, non-commercial apps that ask it from the viewer's own
 * device, which is how Settee asks, and storing one viewer's own episodes' timings.
 *
 * Blocking, like [TmdbClient]: the caller is already off the main thread.
 */
class TheIntroDbClient(
    private val http: OkHttpClient,
    /** Hears TheIntroDB turning down Settee's own request, for a Crash Report (ADR 0010). */
    private val reportFailure: (ServiceFailure) -> Unit = {},
) {

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
        val answer = http.episodeJson(url, "TheIntroDB", reportFailure) ?: return@runCatching null
        // Credits timed in two parts have something between them, but TheIntroDB doesn't say what: it
        // could be a scene or a gap in the timing. Only IntroDB's post-credits marking holds the countdown.
        val credits = answer.segments("credits").firstOrNull()
        val intro = answer.segments("intro").firstOrNull()?.takeIf { it.lengthMillis > 0 }
        val recap = answer.segments("recap").firstOrNull()?.takeIf { it.lengthMillis > 0 }
        if (credits == null && intro == null && recap == null) return@runCatching null
        EpisodeTimings(
            show = EpisodeTimings.tmdbShow(tmdbId),
            season = season,
            episode = episode,
            introStartMillis = intro?.startMillis,
            introEndMillis = intro?.endMillis,
            recapStartMillis = recap?.startMillis,
            recapEndMillis = recap?.endMillis,
            creditsStartMillis = credits?.startMillis,
            creditsEndMillis = credits?.endMillis,
            source = TimingsSource.THE_INTRO_DB,
        )
    }

    /** One timed stretch; a start TheIntroDB leaves null is the very beginning, an end left null is the end. */
    private data class Segment(val startMillis: Long, val endMillis: Long?) {
        val lengthMillis: Long get() = endMillis?.let { it - startMillis } ?: Long.MAX_VALUE
    }

    private fun JsonObject.segments(name: String): List<Segment> =
        (this[name] as? JsonArray).orEmpty().mapNotNull { element ->
            element.objectOrNull()?.let {
                Segment(startMillis = it.millis("start_ms") ?: 0, endMillis = it.millis("end_ms"))
            }
        }

    private companion object {
        val BASE: HttpUrl = "https://api.theintrodb.org/v3/media".toHttpUrl()
    }
}
