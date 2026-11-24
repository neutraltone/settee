/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv.data.remote

import app.opentv.core.HttpStatus
import app.opentv.core.ServiceFailure
import app.opentv.core.ServiceFailure.Operation
import app.opentv.core.ServiceFailure.Service
import app.opentv.data.model.SubtitleKind
import java.io.IOException
import java.time.Instant
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/** What to look for on OpenSubtitles. A TMDB id, where Settee has one, beats a name search. */
sealed interface SubtitleSearch {
    data class Film(val title: String, val year: Int?, val tmdbId: String?) : SubtitleSearch

    data class Episode(val show: String, val showTmdbId: String?, val season: Int, val episode: Int) : SubtitleSearch
}

/** One subtitle OpenSubtitles offers for a title. [fileId] is what [OpenSubtitlesClient.download] takes. */
data class SubtitleResult(
    val fileId: Long,
    val language: String,
    val release: String,
    val downloads: Int,
    val kind: SubtitleKind,
)

/** A downloaded file, and how many downloads the quota has left after it (null when not said). */
class SubtitleFile(val text: String, val fileName: String?, val remaining: Int?)

/** A signed-in OpenSubtitles Account: its token (good for 24 hours) and downloads left today. */
data class OpenSubtitlesSession(val token: String, val remaining: Int?, val allowed: Int?)

/** Why OpenSubtitles couldn't do what was asked. Each becomes a sentence through ErrorWording. */
sealed class OpenSubtitlesFailure(message: String, cause: Throwable? = null) : IOException(message, cause) {
    /** No answer at all, or trouble of OpenSubtitles' own ([status]). */
    class Unreachable(cause: Throwable?, override val status: Int? = null) :
        OpenSubtitlesFailure("OpenSubtitles didn't answer${status?.let { " ($it)" }.orEmpty()}", cause),
        HttpStatus

    /** Nothing for the title, or nothing in the language wanted. */
    class NoResults : OpenSubtitlesFailure("No subtitles found")

    /** Today's downloads are used up. [resetsAt] is UTC epoch millis, when OpenSubtitles said. */
    class DownloadLimit(val signedIn: Boolean, val resetsAt: Long?, cause: Throwable? = null) :
        OpenSubtitlesFailure("Daily OpenSubtitles download limit reached", cause)

    /** Wrong username or password. */
    class SignInRejected(cause: Throwable? = null) :
        OpenSubtitlesFailure("OpenSubtitles turned down the username or password", cause)

    /** The saved token is expired or invalid. */
    class TokenRejected : OpenSubtitlesFailure("OpenSubtitles no longer accepts the sign-in")

    /**
     * OpenSubtitles turned down Settee's own key, before it looked at any account. Retrying never helps
     * and the viewer can't fix it: only a Settee with a key OpenSubtitles accepts can. [status] is
     * 403 with OpenSubtitles' own words, or a 401 to a request that carried only the key.
     */
    class KeyRejected(val status: Int = 403) : OpenSubtitlesFailure("OpenSubtitles turned down Settee's key")

    /**
     * A subtitle downloaded, but this box couldn't keep it: storage full, the write failed, or the file
     * is over the Downloaded Subtitles' cap. Thrown by [app.opentv.data.repo.OpenSubtitles], not this
     * client, and kept apart from [Unreachable] so the viewer isn't sent to check a network that's fine
     * and spend another of the day's downloads on the same failure.
     */
    class NotSaved(cause: Throwable) : OpenSubtitlesFailure("Couldn't keep the subtitle on this box", cause)

    /**
     * OpenSubtitles accepted the sign-in, but this box couldn't seal the password to keep it (a Keystore
     * fault). Thrown by [app.opentv.data.repo.OpenSubtitlesAccount]; signing in again the same way
     * reaches the same Keystore, so it isn't worded as the network.
     */
    class SignInNotSaved(cause: Throwable) : OpenSubtitlesFailure("Couldn't save the sign-in on this box", cause)
}

/**
 * OpenSubtitles' REST API, with Settee's own key.
 *
 * ## One key, Settee's
 * OpenSubtitles bans apps that make viewers bring their own key, so [apiKey] is the one injected
 * at build time (BuildConfig) and there's no field for it anywhere. A build without one never
 * constructs this client.
 *
 * ## Blocking
 * Like [TmdbClient], every call blocks; callers are already on Dispatchers.IO.
 *
 * Never logs: requests carry the key, and sign-in carries the viewer's password.
 */
class OpenSubtitlesClient(
    private val http: OkHttpClient,
    private val apiKey: String,
    appVersion: String,
    private val json: Json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    },
    /** Hears OpenSubtitles turning down Settee's own request, for a Crash Report (ADR 0010). */
    private val reportFailure: (ServiceFailure) -> Unit = {},
) {
    private val userAgent = "Settee v$appVersion"

    /** Free accounts use the main host; [signIn] may name another (VIP accounts), used from then on. */
    @Volatile private var base: HttpUrl = DEFAULT_BASE

    /**
     * The host requests go to, which a signed-in account keeps with its token: after a restart the
     * token is still the one that VIP host issued, so [useHost] puts it back before it's used.
     */
    val host: String get() = base.host

    /** Sends requests to [host] (from an earlier [signIn]); null or one that isn't a host leaves the main one. */
    fun useHost(host: String?) {
        base = host?.takeIf { it.isNotBlank() }?.let { "https://$it/api/v1/".toHttpUrlOrNull() } ?: DEFAULT_BASE
    }

    fun search(query: SubtitleSearch): Result<List<SubtitleResult>> = runCatching {
        val url = base.newBuilder().addPathSegment("subtitles").apply {
            when (query) {
                is SubtitleSearch.Film -> {
                    addQueryParameter("type", "movie")
                    if (query.tmdbId != null) {
                        addQueryParameter("tmdb_id", query.tmdbId)
                    } else {
                        addQueryParameter("query", query.title)
                        query.year?.let { addQueryParameter("year", it.toString()) }
                    }
                }
                is SubtitleSearch.Episode -> {
                    addQueryParameter("type", "episode")
                    if (query.showTmdbId != null) {
                        addQueryParameter("parent_tmdb_id", query.showTmdbId)
                    } else {
                        addQueryParameter("query", query.show)
                    }
                    addQueryParameter("season_number", query.season.toString())
                    addQueryParameter("episode_number", query.episode.toString())
                }
            }
        }.build()
        (call(request(url).get().build(), Operation.SEARCH)["data"] as? JsonArray).orEmpty().mapNotNull(::result)
    }

    /**
     * Downloads [fileId]: OpenSubtitles hands back a temporary link, so the file itself is fetched
     * at once and the link never kept. [token] counts it against that account instead of this
     * address's anonymous allowance.
     */
    fun download(fileId: Long, token: String?): Result<SubtitleFile> = runCatching {
        val payload = buildJsonObject { put("file_id", fileId) }.toString()
        val url = base.newBuilder().addPathSegment("download").build()
        val answer = call(request(url, token).post(payload.toRequestBody(JSON_TYPE)).build(), Operation.DOWNLOAD)
        val link = answer.string("link") ?: throw OpenSubtitlesFailure.Unreachable(null)
        SubtitleFile(fetchFile(link), answer.string("file_name"), answer.int("remaining"))
    }

    /** The file behind a download link, which needs no key: it's already been counted. */
    private fun fetchFile(link: String): String {
        val response = try {
            http.newCall(Request.Builder().url(link).header("User-Agent", userAgent).build()).execute()
        } catch (e: IOException) {
            throw OpenSubtitlesFailure.Unreachable(e)
        }
        return response.use {
            if (it.isSuccessful) it.body.string() else throw OpenSubtitlesFailure.Unreachable(null, it.code)
        }
    }

    /** Signs an OpenSubtitles Account in. The password goes to OpenSubtitles and nowhere else. */
    fun signIn(username: String, password: String): Result<OpenSubtitlesSession> = runCatching {
        val payload = buildJsonObject {
            put("username", username)
            put("password", password)
        }.toString()
        val url = DEFAULT_BASE.newBuilder().addPathSegment("login").build()
        val answer = try {
            call(request(url).post(payload.toRequestBody(JSON_TYPE)).build(), Operation.SIGN_IN)
        } catch (refused: OpenSubtitlesFailure.TokenRejected) {
            throw OpenSubtitlesFailure.SignInRejected(refused)
        }
        val token = answer.string("token") ?: throw OpenSubtitlesFailure.SignInRejected()
        useHost(answer.string("base_url"))
        val user = answer["user"] as? JsonObject
        OpenSubtitlesSession(token, user?.int("remaining_downloads"), user?.int("allowed_downloads"))
    }

    /** The signed-in account's downloads left today. */
    fun account(token: String): Result<OpenSubtitlesSession> = runCatching {
        val url = base.newBuilder().addPathSegments("infos/user").build()
        val data = call(request(url, token).get().build(), Operation.ACCOUNT)["data"] as? JsonObject
        OpenSubtitlesSession(token, data?.int("remaining_downloads"), data?.int("allowed_downloads"))
    }

    private fun request(url: HttpUrl, token: String? = null) = Request.Builder()
        .url(url)
        .header("Api-Key", apiKey)
        .header("User-Agent", userAgent)
        .header("Accept", "application/json")
        .apply { token?.let { header("Authorization", "Bearer $it") } }

    /**
     * Sends [request] and returns its JSON object, turning every way it can fail into an
     * [OpenSubtitlesFailure]. Only a download's 406 is the daily limit. A failure that's Settee's
     * own, such as a 403 or a reply that isn't JSON, is also reported as [operation]'s.
     */
    private fun call(request: Request, operation: Operation): JsonObject {
        // The body is read with the request, so a connection dropped partway is Unreachable, not a
        // reply Settee couldn't read.
        val (status, body) = try {
            http.newCall(request).execute().use { it.code to it.body.string() }
        } catch (e: IOException) {
            throw OpenSubtitlesFailure.Unreachable(e)
        }
        val parsed = runCatching { json.parseToJsonElement(body) }.getOrNull() as? JsonObject
        val succeeded = status in HTTP_SUCCESS
        if (succeeded && parsed != null) return parsed
        val failure = when {
            succeeded -> ServiceFailure.unreadable(Service.OPENSUBTITLES, operation)
            // Signing in sends the viewer's password, and the rest with a token their session: a 401 to
            // either is theirs. Without either, only Settee's key went, so a 401 is the key refused.
            else -> ServiceFailure.ofStatus(
                Service.OPENSUBTITLES,
                operation,
                status,
                viewersCredentials = operation == Operation.SIGN_IN || request.header("Authorization") != null,
            )
        }
        failure?.let(reportFailure)
        throw failureFor(status, parsed, request, operation)
    }

    /** What an answer of [status] means; a success that isn't JSON is OpenSubtitles' trouble too. */
    private fun failureFor(status: Int, answer: JsonObject?, request: Request, operation: Operation) = when {
        // As for the report above: a 401 is the viewer's only when their password or token went with
        // it. A search never sends a token, so telling that viewer they were signed out would send
        // them to sign in, which can't help when it's Settee's key OpenSubtitles turned down.
        // signIn turns its own TokenRejected into SignInRejected.
        status == HTTP_UNAUTHORISED &&
            (operation == Operation.SIGN_IN || request.header("Authorization") != null) ->
            OpenSubtitlesFailure.TokenRejected()
        status == HTTP_UNAUTHORISED -> OpenSubtitlesFailure.KeyRejected(status)
        // Told by its message too: Cloudflare answers 403 with a challenge page, which is passing trouble.
        // The same contains-match as the release check, tools/keys/check_keys.py: change both together.
        status == HTTP_FORBIDDEN && answer?.string("message")?.contains(KEY_REFUSED) == true ->
            OpenSubtitlesFailure.KeyRejected()
        status == HTTP_NOT_ACCEPTABLE && operation == Operation.DOWNLOAD -> OpenSubtitlesFailure.DownloadLimit(
            signedIn = request.header("Authorization") != null,
            resetsAt = resetTime(answer),
        )
        else -> OpenSubtitlesFailure.Unreachable(null, status)
    }

    private companion object {
        val DEFAULT_BASE = "https://api.opensubtitles.com/api/v1/".toHttpUrl()
        val JSON_TYPE = "application/json".toMediaType()
        val HTTP_SUCCESS = 200..299
        const val HTTP_UNAUTHORISED = 401
        const val HTTP_FORBIDDEN = 403
        const val KEY_REFUSED = "You cannot consume this service"
        const val HTTP_NOT_ACCEPTABLE = 406
    }
}

// OpenSubtitles' field types vary like a panel's, so they're read by hand with nullable accessors.
private fun JsonObject.string(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull

private fun JsonObject.int(key: String): Int? =
    (this[key] as? JsonPrimitive)?.let { it.intOrNull ?: it.contentOrNull?.toIntOrNull() }

private fun JsonObject.bool(key: String): Boolean =
    (this[key] as? JsonPrimitive)?.let { it.booleanOrNull ?: (it.contentOrNull == "1") } ?: false
private fun resetTime(answer: JsonObject?): Long? = answer?.string("reset_time_utc")
    ?.let { runCatching { Instant.parse(it).toEpochMilli() }.getOrNull() }

private fun result(item: JsonElement): SubtitleResult? {
    val a = (item as? JsonObject)?.get("attributes") as? JsonObject
    val file = (a?.get("files") as? JsonArray)?.firstOrNull() as? JsonObject
    // A result with no file has nothing to download, so it isn't offered.
    val fileId = file?.get("file_id")?.jsonPrimitive?.longOrNull
    return if (a == null || fileId == null) {
        null
    } else {
        SubtitleResult(
            fileId = fileId,
            language = a.string("language").orEmpty(),
            release = a.string("release").orEmpty(),
            downloads = a.int("download_count") ?: 0,
            kind = when {
                a.bool("foreign_parts_only") -> SubtitleKind.FORCED
                a.bool("hearing_impaired") -> SubtitleKind.HEARING_IMPAIRED
                else -> SubtitleKind.STANDARD
            },
        )
    }
}
