/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv.data.remote

import android.util.Log
import app.opentv.core.HttpStatus
import app.opentv.data.model.StremioStream
import java.io.IOException
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * Why an add-on couldn't be used. Each says what the viewer can act on, so `ErrorWording.ofAddon`
 * words it by type; none of the messages hold the add-on's address, which can carry a debrid key.
 */
sealed class AddonFailure(message: String, cause: Throwable? = null) :
    IOException(message, cause),
    HttpStatus {
    /** No answer at all: no network, an unknown host, a refused connection, a timeout. */
    class Unreachable(cause: Throwable) : AddonFailure("The add-on didn't answer", cause) {
        override val status: Int? = null
    }

    /** The add-on's server answered with trouble of its own, such as a 5xx. */
    class Http(override val status: Int) : AddonFailure("The add-on answered $status")

    /**
     * What answered isn't an add-on: a 404 ([status]), a web page such as a Wi-Fi sign-in, or JSON
     * that isn't a manifest. The address is what's wrong, not something to wait out.
     */
    class NotAnAddon(override val status: Int? = null) : AddonFailure("That isn't an add-on")
}

/**
 * A thin, read-only client for the open Stremio add-on protocol — the same protocol Stremio and
 * many Kodi add-ons speak. OpenTV ships with no add-ons and hosts no content; this only talks to
 * manifest URLs the user has pasted in themselves.
 *
 * ## What it does
 * - [fetchManifest] fetches an add-on's `manifest.json` and returns its display name if it looks
 *   like a real add-on (has an id + name), so the settings screen can validate a pasted URL, and
 *   otherwise throws an [AddonFailure] saying why not.
 * - [streams] asks an add-on for the streams it offers for a title and returns only the ones with a
 *   direct playable URL. Debrid-backed add-ons resolve a title to such a URL internally (using the
 *   debrid key baked into the user's personalised manifest URL), so OpenTV never handles a debrid
 *   key and never touches a torrent — it just plays the URL the add-on hands back. An add-on that
 *   can't be asked throws an [AddonFailure], so the caller can tell that apart from one with nothing.
 *
 * ## Blocking on purpose
 * Methods block on the calling thread; callers already run them inside `withContext(Dispatchers.IO)`,
 * matching [TmdbClient].
 */
class StremioClient(
    private val http: OkHttpClient,
    private val json: Json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    },
) {

    /**
     * Fetch and lightly validate an add-on manifest: the add-on's display name, or an [AddonFailure]
     * when the address doesn't resolve to something that looks like a Stremio manifest (id + name).
     */
    fun fetchManifest(manifestUrl: String): String {
        val url = manifestUrl.trim().toHttpUrlOrNull() ?: throw AddonFailure.NotAnAddon()
        val manifest = get(url) as? JsonObject
        val hasId = manifest?.text("id")?.isNotBlank() == true
        return manifest?.text("name")?.takeIf { hasId && it.isNotBlank() } ?: throw AddonFailure.NotAnAddon()
    }

    /**
     * The streams [addonManifestUrl] offers for a title. [type] is "movie" or "series"; [id] is the
     * IMDb id — `tt1234567` for a film, `tt1234567:1:3` for series season 1 episode 3. Only entries
     * with a direct `url` are returned (see the class note on why torrents are skipped).
     */
    fun streams(addonManifestUrl: String, addonName: String, type: String, id: String): List<StremioStream> {
        // A manifest URL is "<base>/manifest.json"; resource URLs hang off the same base.
        val base = addonManifestUrl.trim().removeSuffix("manifest.json").trimEnd('/')
        val url = "$base/stream/$type/$id.json".toHttpUrlOrNull() ?: throw AddonFailure.NotAnAddon()
        val reply = try {
            get(url)
        } catch (notFound: AddonFailure.NotAnAddon) {
            // Some add-ons answer 404 for a title they have nothing for: an answer, not a failure.
            if (notFound.status == HTTP_NOT_FOUND) return emptyList() else throw notFound
        }
        val streams = (reply as? JsonObject)?.get("streams") as? JsonArray ?: return emptyList()
        return streams.mapNotNull { element ->
            val s = element as? JsonObject ?: return@mapNotNull null
            val streamUrl = s.text("url")?.takeIf { it.isNotBlank() }
                ?: return@mapNotNull null // infoHash-only (raw torrent) — skipped on purpose
            val label = (s.text("title") ?: s.text("name"))
                ?.replace('\n', ' ')?.trim()?.takeIf { it.isNotBlank() } ?: "Stream"
            StremioStream(url = streamUrl, title = label, addonName = addonName)
        }
    }

    /** The JSON at [url], or the [AddonFailure] that stopped it. Never logs the address. */
    private fun get(url: HttpUrl): JsonElement {
        val body = read(url).getOrThrow()
        // A Wi-Fi sign-in page or any other web page answers 200 with HTML.
        return try {
            json.parseToJsonElement(body)
        } catch (_: SerializationException) {
            throw AddonFailure.NotAnAddon()
        }
    }

    private fun read(url: HttpUrl): Result<String> {
        val request = Request.Builder().url(url).header("User-Agent", "OpenTV").build()
        return try {
            http.newCall(request).execute().use { response ->
                val status = response.code
                when {
                    status == HTTP_NOT_FOUND -> Result.failure(AddonFailure.NotAnAddon(status))
                    !response.isSuccessful -> Result.failure(AddonFailure.Http(status))
                    else -> Result.success(response.body.string())
                }
            }
        } catch (e: IOException) {
            Log.w(TAG, "Stremio request failed: ${e.javaClass.simpleName}")
            Result.failure(AddonFailure.Unreachable(e))
        }
    }

    // By hand rather than with jsonPrimitive, which throws on an object or array where text was expected (#364).
    private fun JsonObject.text(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull

    private companion object {
        const val TAG = "StremioClient"
        const val HTTP_NOT_FOUND = 404
    }
}
