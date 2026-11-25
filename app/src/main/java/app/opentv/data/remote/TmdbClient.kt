/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv.data.remote

import android.util.Log
import app.opentv.core.ServiceFailure
import app.opentv.core.ServiceFailure.Operation
import app.opentv.core.ServiceFailure.Service
import app.opentv.data.model.knownTmdbId
import app.opentv.data.parser.parseReleaseDate
import java.io.IOException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
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

/** One title TMDB's search found: a film when [isMovie], else a show. */
data class TmdbHit(
    val tmdbId: String,
    val isMovie: Boolean,
    val name: String,
    val year: Int?,
    val posterUrl: String?,
    val backdropUrl: String?,
    val overview: String?,
)

/** One episode TMDB lists for a show; [airedMillis] is when it first aired (UTC), null if TMDB doesn't say. */
data class TmdbEpisode(
    val season: Int,
    val number: Int,
    val title: String,
    val overview: String?,
    val runtimeMinutes: Int?,
    val stillUrl: String?,
    val airedMillis: Long?,
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
// One method per thing Settee asks TMDB, each reading its own reply: split up, they'd only be scattered.
@Suppress("TooManyFunctions")
class TmdbClient(
    private val http: OkHttpClient,
    /** The built-in TMDB Key; blank in a build that has none. */
    apiKey: String,
    private val json: Json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    },
    /** Hears TMDB turning down Settee's own request, for a Crash Report (ADR 0010). */
    private val reportFailure: (ServiceFailure) -> Unit = {},
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
        val read = { url: HttpUrl, operation: Operation -> fetch(url, key, operation) }
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
        val read = { url: HttpUrl, operation: Operation -> fetch(url, key, operation) }
        val asked = TmdbTitle(isMovie, title, year, tmdbId)
        val id = asked.tmdbId ?: searchId(asked, read) ?: return@runCatching null
        val builder = TMDB_BASE.newBuilder()
            .addPathSegment(if (isMovie) "movie" else "tv")
            .addPathSegment(id)
        if (!isMovie) builder.addPathSegment("external_ids")
        val o = read(builder.build(), Operation.DETAILS)?.jsonObject ?: return@runCatching null
        o["imdb_id"]?.jsonPrimitive?.contentOrNull?.takeIf { it.startsWith("tt") }
    }.onFailure { Log.w(TAG, "TMDB request failed: ${it.javaClass.simpleName}") }

    /**
     * TMDB's titles for what a viewer typed, films and shows alike (people left out), best match first,
     * for search's titles the Provider doesn't carry. Empty with no key; a failure when TMDB didn't answer.
     */
    fun searchTitles(query: String): Result<List<TmdbHit>> = runCatching {
        val key = credential ?: return@runCatching emptyList()
        val url = TMDB_BASE.newBuilder()
            .addPathSegment("search")
            .addPathSegment("multi")
            .addQueryParameter("query", query.trim())
            .addQueryParameter("include_adult", "false")
            .build()
        val results = fetch(url, key, Operation.SEARCH)?.jsonObject?.get("results") as? JsonArray
        results.orEmpty().mapNotNull { result ->
            (result as? JsonObject)?.let { hitOf(it)?.withArt(IMG_POSTER, IMG_BACKDROP) }
        }
    }.onFailure { Log.w(TAG, "TMDB request failed: ${it.javaClass.simpleName}") }

    /**
     * The film (or, when not [isMovie], the show) TMDB knows by the IMDb id [imdbId], for a title kept
     * from an add-on on another device, which arrives by sync as no more than that id. Null with no key or
     * no match; a failure when TMDB didn't answer.
     */
    fun titleByImdbId(imdbId: String, isMovie: Boolean): Result<TmdbHit?> = runCatching {
        val key = credential ?: return@runCatching null
        val url = TMDB_BASE.newBuilder()
            .addPathSegment("find")
            .addPathSegment(imdbId)
            .addQueryParameter("external_source", "imdb_id")
            .build()
        val found = fetch(url, key, Operation.DETAILS)?.jsonObject
            ?.get(if (isMovie) "movie_results" else "tv_results") as? JsonArray
        // A look-up's results don't always say what they are, which hitOf goes by: it's whichever was asked.
        val mediaType = JsonPrimitive(if (isMovie) "movie" else "tv")
        (found?.firstOrNull() as? JsonObject)
            ?.let { hitOf(JsonObject(it + ("media_type" to mediaType)))?.withArt(IMG_POSTER, IMG_BACKDROP) }
    }.onFailure { Log.w(TAG, "TMDB request failed: ${it.javaClass.simpleName}") }

    /**
     * Every episode TMDB lists for the show [tmdbId], Specials (season 0) left out, for a show kept from
     * an add-on, which has no panel to list them. Empty with no key or for a show TMDB doesn't know; a
     * failure when TMDB didn't answer, so its page can offer Try again rather than say it has none.
     */
    fun seasonEpisodes(tmdbId: String): Result<List<TmdbEpisode>> = runCatching {
        val key = credential ?: return@runCatching emptyList()
        val show = TMDB_BASE.newBuilder().addPathSegment("tv").addPathSegment(tmdbId).build()
        val seasons = (fetch(show, key, Operation.DETAILS)?.jsonObject?.get("seasons") as? JsonArray)
            .orEmpty()
            .mapNotNull { (it as? JsonObject)?.int("season_number") }
            .filter { it > 0 }
            .distinct()
            .sorted()
        // Seasons appended to the show's own request, as many as TMDB takes in one, rather than one
        // request a season: a long-running show would otherwise cost dozens on a Stick's slow network.
        seasons.chunked(SEASONS_PER_REQUEST).flatMap { asked ->
            val appended = asked.joinToString(",") { "season/$it" }
            val url = show.newBuilder().addQueryParameter("append_to_response", appended).build()
            val reply = fetch(url, key, Operation.DETAILS)?.jsonObject
            asked.flatMap { season ->
                (reply?.get("season/$season") as? JsonObject)?.get("episodes").let { it as? JsonArray }.orEmpty()
                    .mapNotNull { (it as? JsonObject)?.let { o -> episodeOf(o, season) } }
            }
        }
    }.onFailure { Log.w(TAG, "TMDB request failed: ${it.javaClass.simpleName}") }

    /** The TMDB id of the show called [title], by a title(+year) search; null with no key or no match. */
    fun seriesId(title: String, year: Int?): String? {
        val key = credential ?: return null
        return TmdbTitle(isMovie = false, title, year, providersId = null)
            .takeIf { it.query.isNotBlank() }
            ?.let { asked ->
                // Only the exception's type: an HTTP client's message can hold the URL, and with it an API Key.
                runCatching { searchId(asked) { url, operation -> fetch(url, key, operation) } }
                    .onFailure { Log.w(TAG, "TMDB request failed: ${it.javaClass.simpleName}") }
                    .getOrNull()
            }
    }

    private fun details(isMovie: Boolean, id: String, read: (HttpUrl, Operation) -> JsonElement?): TmdbMeta? {
        val url = TMDB_BASE.newBuilder()
            .addPathSegment(if (isMovie) "movie" else "tv")
            .addPathSegment(id)
            // Recommendations ride on the details request, so suggestions cost no request of their own.
            .addQueryParameter("append_to_response", "credits,recommendations")
            .build()
        val o = read(url, Operation.DETAILS)?.jsonObject ?: return null

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

    /**
     * The answer to [url], asked as [operation]; null for a 404, which is TMDB saying there's nothing.
     * Throws if TMDB didn't answer, reporting it when the failure is Settee's own (a refused key, a
     * search's 404, a reply it couldn't read).
     */
    private fun fetch(url: HttpUrl, key: Credential, operation: Operation): JsonElement? =
        http.newCall(authorised(url, key)).execute().use { response ->
            when {
                // A search answers no match with an empty list, so its 404 can only be Settee asking
                // wrongly. A look-up by id answers 404 for a title TMDB doesn't know, and for an address
                // it doesn't have alike: no telling them apart, so that 404 is never reported.
                response.code == HTTP_NOT_FOUND -> {
                    if (operation == Operation.SEARCH) {
                        ServiceFailure.ofStatus(Service.TMDB, operation, response.code)?.let(reportFailure)
                    }
                    null
                }
                !response.isSuccessful -> {
                    ServiceFailure.ofStatus(Service.TMDB, operation, response.code)?.let(reportFailure)
                    throw IOException("TMDB answered ${response.code}")
                }
                // Every endpoint Settee asks answers an object. Lenient parsing reads a page as a bare
                // string, so a page in front of the API is caught here, not by the parser.
                else -> response.body.string()
                    .let { body -> runCatching { json.parseToJsonElement(body) }.getOrNull() as? JsonObject }
                    ?: run {
                        reportFailure(ServiceFailure.unreadable(Service.TMDB, operation))
                        throw IOException("TMDB's reply couldn't be read")
                    }
            }
        }

    private companion object {
        const val TAG = "TmdbClient"
        const val IMG_POSTER = "https://image.tmdb.org/t/p/w500"
        const val IMG_BACKDROP = "https://image.tmdb.org/t/p/w1280"
        const val TOP_CAST = 12
        const val TOP_STUDIOS = 4
        const val HTTP_NOT_FOUND = 404

        /** The crew jobs that count as writing it: TMDB files writers under several. */
        val WRITING_JOBS = setOf("Screenplay", "Writer", "Story", "Novel", "Author")
    }
}

private val TMDB_BASE: HttpUrl = "https://api.themoviedb.org/3".toHttpUrl()

/** Finds the best-matching TMDB id, retrying without the year filter if a year search comes back empty. */
private fun searchId(title: TmdbTitle, read: (HttpUrl, Operation) -> JsonElement?): String? {
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
        val results = read(b.build(), Operation.SEARCH)?.jsonObject?.get("results")?.jsonArray ?: return null
        return results.firstOrNull()?.jsonObject?.get("id")?.jsonPrimitive?.contentOrNull
    }
    return run(withYear = year != null) ?: if (year != null) run(withYear = false) else null
}

/**
 * [raw] as TMDB is searched for it: a trailing "(1995)" goes, but a bare trailing year stays unless it
 * is the film's own [year], because it is often part of the name ("Blade Runner 2049", "1917"). If
 * leaving the year out would leave nothing ("1917" from 1917), the whole name is searched for.
 */
private fun searchTitle(raw: String, year: Int?): String {
    val cleaned = raw.replace(WHITESPACE, " ").trim()
    val withoutYear = BRACKETED_YEAR.find(cleaned)?.let { cleaned.removeRange(it.range) }
        ?: BARE_YEAR.find(cleaned)?.takeIf { year != null && it.groupValues[1] == year.toString() }
            ?.let { cleaned.removeRange(it.range) }
    return withoutYear?.trim()?.takeIf { it.isNotEmpty() } ?: cleaned
}

/**
 * One result of TMDB's multi-search, read by hand, each field nullable, so one odd result doesn't cost
 * the rest; null for a person, or a result without an id or a name. Its art paths are as TMDB gives them.
 */
private fun hitOf(o: JsonObject): TmdbHit? {
    fun text(key: String) = (o[key] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }
    val isMovie = when (text("media_type")) {
        "movie" -> true
        "tv" -> false
        else -> null
    }
    val id = knownTmdbId(text("id"))
    val name = isMovie?.let { text(if (it) "title" else "name") }
    if (isMovie == null || id == null || name == null) return null
    return TmdbHit(
        tmdbId = id,
        isMovie = isMovie,
        name = name,
        year = text(if (isMovie) "release_date" else "first_air_date")?.take(YEAR_DIGITS)?.toIntOrNull(),
        posterUrl = text("poster_path"),
        backdropUrl = text("backdrop_path"),
        overview = text("overview"),
    )
}

private fun JsonObject.int(key: String): Int? = (this[key] as? JsonPrimitive)?.contentOrNull?.toIntOrNull()

/**
 * One episode of a TMDB season, read by hand like [hitOf]; null without an episode number. Its still is
 * a whole address.
 */
private fun episodeOf(o: JsonObject, season: Int): TmdbEpisode? {
    fun text(key: String) = (o[key] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }
    val number = o.int("episode_number") ?: return null
    return TmdbEpisode(
        season = season,
        number = number,
        title = text("name") ?: "",
        overview = text("overview"),
        runtimeMinutes = o.int("runtime"),
        stillUrl = text("still_path")?.let { IMG_STILL + it },
        airedMillis = parseReleaseDate(text("air_date")),
    )
}

private const val IMG_STILL = "https://image.tmdb.org/t/p/w500"

/** TMDB takes up to 20 appended answers on one request. */
private const val SEASONS_PER_REQUEST = 20

/** This hit with its art paths made whole addresses under [poster] and [backdrop]. */
private fun TmdbHit.withArt(poster: String, backdrop: String) =
    copy(posterUrl = posterUrl?.let { poster + it }, backdropUrl = backdropUrl?.let { backdrop + it })

private const val YEAR_DIGITS = 4

private val WHITESPACE = Regex("""\s+""")
private val BRACKETED_YEAR = Regex("""\s*\((?:19|20)\d{2}\)$""")
private val BARE_YEAR = Regex("""(?:^|\s)((?:19|20)\d{2})$""")

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
    val query: String get() = searchTitle(title, year)
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
