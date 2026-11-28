/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv.data.remote

import app.opentv.core.ServiceFailure.Operation
import app.opentv.data.model.knownTmdbId
import app.opentv.data.parser.parseReleaseDate
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject

/*
 * What only titles from add-ons ask TMDB (ADR 0011): search across films and shows, a title by its IMDb
 * id, and a show's episodes. Apart from TmdbClient's metadata back-fill, which every Provider's titles
 * use; each goes through TmdbClient.lookUp, so the key, failure reports and logging stay in one place.
 */

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
 * TMDB's titles for what a viewer typed, films and shows alike (people left out), best match first,
 * for search's titles the Provider doesn't carry. Empty with no key; a failure when TMDB didn't answer.
 */
fun TmdbClient.searchTitles(query: String): Result<List<TmdbHit>> = lookUp(none = emptyList()) { read ->
    val url = TMDB_BASE.newBuilder()
        .addPathSegment("search")
        .addPathSegment("multi")
        .addQueryParameter("query", query.trim())
        .addQueryParameter("include_adult", "false")
        .build()
    val results = read(url, Operation.SEARCH)?.jsonObject?.get("results") as? JsonArray
    results.orEmpty().mapNotNull { result -> (result as? JsonObject)?.let { hitOf(it)?.withArt() } }
}

/**
 * The film (or, when not [isMovie], the show) TMDB knows by the IMDb id [imdbId], for a title kept
 * from an add-on on another device, which arrives by sync as no more than that id. Null with no key or
 * no match; a failure when TMDB didn't answer.
 */
fun TmdbClient.titleByImdbId(imdbId: String, isMovie: Boolean): Result<TmdbHit?> = lookUp(none = null) { read ->
    val url = TMDB_BASE.newBuilder()
        .addPathSegment("find")
        .addPathSegment(imdbId)
        .addQueryParameter("external_source", "imdb_id")
        .build()
    val found = read(url, Operation.DETAILS)?.jsonObject
        ?.get(if (isMovie) "movie_results" else "tv_results") as? JsonArray
    // A look-up's results don't always say what they are, which hitOf goes by: it's whichever was asked.
    val mediaType = JsonPrimitive(if (isMovie) "movie" else "tv")
    (found?.firstOrNull() as? JsonObject)?.let { hitOf(JsonObject(it + ("media_type" to mediaType)))?.withArt() }
}

/**
 * Every episode TMDB lists for the show [tmdbId], Specials (season 0) left out, for a show kept from
 * an add-on, which has no panel to list them. Empty with no key or for a show TMDB doesn't know; a
 * failure when TMDB didn't answer, so its page can offer Try again rather than say it has none.
 */
fun TmdbClient.seasonEpisodes(tmdbId: String): Result<List<TmdbEpisode>> = lookUp(none = emptyList()) { read ->
    val show = TMDB_BASE.newBuilder().addPathSegment("tv").addPathSegment(tmdbId).build()
    val seasons = (read(show, Operation.DETAILS)?.jsonObject?.get("seasons") as? JsonArray)
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
        val reply = read(url, Operation.DETAILS)?.jsonObject
        asked.flatMap { season ->
            (reply?.get("season/$season") as? JsonObject)?.get("episodes").let { it as? JsonArray }.orEmpty()
                .mapNotNull { (it as? JsonObject)?.let { o -> episodeOf(o, season) } }
        }
    }
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
 * a whole address, at a poster's size.
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
        stillUrl = text("still_path")?.let { TMDB_IMG_POSTER + it },
        airedMillis = parseReleaseDate(text("air_date")),
    )
}

/** This hit with its art paths made whole addresses, at the sizes the metadata back-fill uses. */
private fun TmdbHit.withArt() = copy(
    posterUrl = posterUrl?.let { TMDB_IMG_POSTER + it },
    backdropUrl = backdropUrl?.let { TMDB_IMG_BACKDROP + it },
)

/** TMDB takes up to 20 appended answers on one request. */
private const val SEASONS_PER_REQUEST = 20

private const val YEAR_DIGITS = 4
