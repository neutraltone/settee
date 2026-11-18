/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv.data.remote

import android.util.Log
import app.opentv.data.model.knownTmdbId
import app.opentv.data.parser.parseReleaseDate
import java.io.IOException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * The metadata TMDB can supply to fill gaps a provider left blank. Every field is nullable — TMDB
 * is only ever a *fallback*, so a caller takes what it has and only where its own data is missing.
 */
data class TmdbMeta(
    val tmdbId: String? = null,
    val posterUrl: String? = null,
    val backdropUrl: String? = null,
    val overview: String? = null,
    val cast: String? = null,
    /** Every director, comma-separated: TMDB credits co-directors, where a provider's field is the whole crew. */
    val director: String? = null,
    /** Who wrote it (screenplay, story, novel), comma-separated. Films only. */
    val writers: String? = null,
    /** The production companies, comma-separated. Films only. */
    val studios: String? = null,
    val genre: String? = null,
    val rating: Double? = null,
    val year: Int? = null,
    /** Release day (a film) or first air day (a show), UTC epoch millis at midnight. */
    val releaseDate: Long? = null,
    /** TMDB ids of the titles TMDB recommends for this one, in its order; same kind as this title. */
    val recommendations: List<String> = emptyList(),
)

/**
 * A thin, read-only TMDB v3 client used to enrich VOD detail pages when the provider's own
 * metadata is thin — the "match Plex" back-fill for posters, backdrops, synopsis, cast, director
 * and genre.
 *
 * ## Settee's own key, built in
 * The key is Settee's, set at build time (`BuildConfig.TMDB_API_KEY`) and passed in, never entered
 * by a viewer. It can be the "API Key" (v3) or the "API Read Access Token" (v4); [authorised] sends
 * whichever it is. It can't be kept secret in an APK and needn't be: TMDB rate-limits by IP address.
 * A build without one (forks, CI's PR builds, a developer's with no key) has [isConfigured] false and every lookup
 * short-circuits to null, so TMDB is never asked and nothing TMDB fills shows broken.
 *
 * ## Blocking on purpose
 * The methods block on the calling thread. The one caller ([app.opentv.data.repo.CatalogRepository]
 * detail back-fill) is already inside `withContext(Dispatchers.IO)`, matching how the Xtream client
 * is used, so there is no value in another coroutine hop here.
 */
class TmdbClient(
    private val http: OkHttpClient,
    /** The built-in TMDB Key; blank in a build that has none. */
    apiKey: String,
    private val json: Json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    },
) {

    private val credential: Credential? = Credential.of(apiKey)

    /** True when the build has a key. Callers gate on this so nothing runs without one. */
    fun isConfigured(): Boolean = credential != null

    /**
     * Metadata for a film: by [tmdbId] when the provider already gave one, else a title(+year)
     * search. No match (success with null), not to be asked about again, is told apart from TMDB not
     * answering (a failure), to be asked again next time.
     */
    fun movieMetaOrFailure(title: String, year: Int?, tmdbId: String?): Result<TmdbMeta?> =
        metaOrFailure(TmdbTitle(isMovie = true, title, year, tmdbId))

    /** As [movieMetaOrFailure], for a show. TMDB has no director for TV, so that stays null. */
    fun seriesMetaOrFailure(title: String, year: Int?, tmdbId: String?): Result<TmdbMeta?> =
        metaOrFailure(TmdbTitle(isMovie = false, title, year, tmdbId))

    private fun metaOrFailure(title: TmdbTitle): Result<TmdbMeta?> = runCatching {
        val key = credential ?: return@runCatching null
        if (title.query.isBlank() && title.tmdbId == null) return@runCatching null
        val read = { url: HttpUrl -> fetch(url, key) }
        val id = title.tmdbId ?: searchId(title, read)
        id?.let { details(title.isMovie, it, read) }
    }

    /**
     * The IMDb id (`tt…`) for a title, for handing to Stremio add-ons. Uses the provider's TMDB id
     * when it gave one, otherwise a title(+year) search, then reads `imdb_id` from the movie details
     * (top-level) or the TV `external_ids` endpoint. Null with no key, no match, or no IMDb mapping;
     * a failure when TMDB didn't answer, so a film's Add-on sources can say so rather than that
     * nothing was found.
     */
    fun imdbId(title: String, year: Int?, isMovie: Boolean, tmdbId: String?): Result<String?> = runCatching {
        val key = credential ?: return@runCatching null
        val read = { url: HttpUrl -> fetch(url, key) }
        val asked = TmdbTitle(isMovie, title, year, tmdbId)
        val id = asked.tmdbId ?: searchId(asked, read) ?: return@runCatching null
        val builder = TMDB_BASE.newBuilder()
            .addPathSegment(if (isMovie) "movie" else "tv")
            .addPathSegment(id)
        if (!isMovie) builder.addPathSegment("external_ids")
        val o = read(builder.build())?.jsonObject ?: return@runCatching null
        o["imdb_id"]?.jsonPrimitive?.contentOrNull?.takeIf { it.startsWith("tt") }
    }.onFailure { Log.w(TAG, "TMDB request failed: ${it.javaClass.simpleName}") }

    /** The TMDB id of the show called [title], by a title(+year) search; null with no key or no match. */
    fun seriesId(title: String, year: Int?): String? {
        val key = credential ?: return null
        return TmdbTitle(isMovie = false, title, year, providersId = null)
            .takeIf { it.query.isNotBlank() }
            ?.let { asked -> searchId(asked) { get(it, key) } }
    }

    /** Finds the best-matching TMDB id, retrying without the year filter if a year search comes back empty. */
    private fun searchId(title: TmdbTitle, read: (HttpUrl) -> JsonElement?): String? {
        val (isMovie, _, year) = title
        fun run(withYear: Boolean): String? {
            val b = TMDB_BASE.newBuilder()
                .addPathSegment("search")
                .addPathSegment(if (isMovie) "movie" else "tv")
                .addQueryParameter("query", title.query)
                .addQueryParameter("include_adult", "false")
            if (withYear && year != null) {
                b.addQueryParameter(if (isMovie) "year" else "first_air_date_year", year.toString())
            }
            val results = read(b.build())?.jsonObject?.get("results")?.jsonArray ?: return null
            return results.firstOrNull()?.jsonObject?.get("id")?.jsonPrimitive?.contentOrNull
        }
        return run(withYear = year != null) ?: if (year != null) run(withYear = false) else null
    }

    private fun details(isMovie: Boolean, id: String, read: (HttpUrl) -> JsonElement?): TmdbMeta? {
        val url = TMDB_BASE.newBuilder()
            .addPathSegment(if (isMovie) "movie" else "tv")
            .addPathSegment(id)
            // Recommendations ride on the details request, so suggestions cost no request of their own.
            .addQueryParameter("append_to_response", "credits,recommendations")
            .build()
        val o = read(url)?.jsonObject ?: return null

        val poster = o["poster_path"]?.jsonPrimitive?.contentOrNull
        val backdrop = o["backdrop_path"]?.jsonPrimitive?.contentOrNull
        val overview = o["overview"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
        val genre = o["genres"]?.jsonArray
            ?.mapNotNull { it.jsonObject["name"]?.jsonPrimitive?.contentOrNull }
            ?.joinToString(", ")?.takeIf { it.isNotBlank() }

        val credits = o["credits"]?.jsonObject
        val cast = credits?.get("cast")?.jsonArray
            ?.take(TOP_CAST)
            ?.mapNotNull { it.jsonObject["name"]?.jsonPrimitive?.contentOrNull }
            ?.joinToString(", ")?.takeIf { it.isNotBlank() }
        val crew = credits?.get("crew")?.jsonArray?.map { it.jsonObject }.orEmpty().takeIf { isMovie }.orEmpty()
        fun crewNamed(jobs: Set<String>) = crew
            .filter { it["job"]?.jsonPrimitive?.contentOrNull in jobs }
            .mapNotNull { it["name"]?.jsonPrimitive?.contentOrNull }
            .distinct()
            .joinToString(", ")
            .takeIf { it.isNotBlank() }
        val director = crewNamed(setOf("Director"))
        val writers = crewNamed(WRITING_JOBS)
        val studios = o["production_companies"]?.jsonArray.takeIf { isMovie }
            ?.mapNotNull { it.jsonObject["name"]?.jsonPrimitive?.contentOrNull }
            ?.take(TOP_STUDIOS)
            ?.joinToString(", ")?.takeIf { it.isNotBlank() }

        val recommendations = o["recommendations"]?.jsonObject?.get("results")?.jsonArray
            ?.mapNotNull { knownTmdbId(it.jsonObject["id"]?.jsonPrimitive?.contentOrNull) }
            ?.distinct()
            .orEmpty()

        val rating = o["vote_average"]?.jsonPrimitive?.doubleOrNull?.takeIf { it > 0.0 }
        val dateStr = (o["release_date"] ?: o["first_air_date"])?.jsonPrimitive?.contentOrNull
        val year = dateStr?.take(4)?.toIntOrNull()

        return TmdbMeta(
            tmdbId = id,
            posterUrl = poster?.let { IMG_POSTER + it },
            backdropUrl = backdrop?.let { IMG_BACKDROP + it },
            overview = overview,
            cast = cast,
            director = director,
            writers = writers,
            studios = studios,
            genre = genre,
            rating = rating,
            year = year,
            releaseDate = parseReleaseDate(dateStr),
            recommendations = recommendations,
        )
    }

    // Only the exception's type: an HTTP client's message can hold the URL, and with it an API Key.
    private fun get(url: HttpUrl, key: Credential): JsonElement? = runCatching { fetch(url, key) }
        .onFailure { Log.w(TAG, "TMDB request failed: ${it.javaClass.simpleName}") }
        .getOrNull()

    /** The answer to [url]; null for a 404, which is TMDB saying there's nothing. Throws if TMDB didn't answer. */
    private fun fetch(url: HttpUrl, key: Credential): JsonElement? =
        http.newCall(authorised(url, key)).execute().use { response ->
            when {
                response.code == HTTP_NOT_FOUND -> null
                !response.isSuccessful -> throw IOException("TMDB answered ${response.code}")
                else -> json.parseToJsonElement(response.body.string())
            }
        }

    private companion object {
        const val TAG = "TmdbClient"
        val TMDB_BASE: HttpUrl = "https://api.themoviedb.org/3".toHttpUrl()
        const val IMG_POSTER = "https://image.tmdb.org/t/p/w500"
        const val IMG_BACKDROP = "https://image.tmdb.org/t/p/w1280"
        const val TOP_CAST = 12
        const val TOP_STUDIOS = 4
        const val HTTP_NOT_FOUND = 404

        /** The crew jobs that count as writing it: TMDB files writers under several. */
        val WRITING_JOBS = setOf("Screenplay", "Writer", "Story", "Novel", "Author")
    }
}

/** Strips a trailing "(2023)"/year and collapses whitespace so the title searches cleanly. */
private fun searchTitle(raw: String): String = raw.replace(Regex("""\(?(19|20)\d{2}\)?\s*$"""), "")
    .replace(Regex("""\s+"""), " ")
    .trim()

/**
 * Whether [key] is TMDB's "API Read Access Token" (v4) rather than its "API Key" (v3): the token is
 * a JWT, three base64url parts with a JSON header (so `eyJ…`), where the key is 32 hex digits.
 */
private fun isReadAccessToken(key: String): Boolean {
    val parts = key.split('.')
    return parts.size == JWT_PARTS && parts[0].startsWith("eyJ") && parts.all { it.isNotEmpty() }
}

private const val JWT_PARTS = 3

/**
 * A title as TMDB is asked about it: [tmdbId] when the provider gave a real one, else [title] and
 * [year] to search. The 0 panels send for a title they haven't matched counts as none, or TMDB would
 * be asked for title 0, answer 404, and the title would never be searched for.
 */
private data class TmdbTitle(val isMovie: Boolean, val title: String, val year: Int?, val providersId: String?) {
    val tmdbId: String? get() = knownTmdbId(providersId)
    val query: String get() = searchTitle(title)
}

/** The built-in TMDB Key, with which kind it is worked out once for every request a look-up makes. */
private class Credential private constructor(val key: String, val readAccessToken: Boolean) {
    companion object {
        /** [key] as a credential; null when there's no key. */
        fun of(key: String): Credential? =
            key.trim().takeIf { it.isNotEmpty() }?.let { Credential(it, isReadAccessToken(it)) }
    }
}

/**
 * The one place a request gets the [credential], so every call sends it the same way: a Read
 * Access Token (v4) as a Bearer header, which TMDB rejects as an `api_key`, and an API Key (v3)
 * as that parameter, which TMDB rejects as a Bearer.
 */
private fun authorised(url: HttpUrl, credential: Credential): Request {
    val builder = Request.Builder().header("User-Agent", "OpenTV")
    val key = credential.key
    return if (credential.readAccessToken) {
        builder.url(url).header("Authorization", "Bearer $key").build()
    } else {
        builder.url(url.newBuilder().addQueryParameter("api_key", key).build()).build()
    }
}
