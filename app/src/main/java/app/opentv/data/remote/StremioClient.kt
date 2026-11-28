/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv.data.remote

import android.util.Log
import app.opentv.core.HttpStatus
import app.opentv.data.model.AddonManifest
import app.opentv.data.model.StreamLink
import app.opentv.data.model.StremioStream
import java.io.IOException
import java.net.HttpURLConnection
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull
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

    /** The add-on's server answered with trouble of its own: a 5xx. */
    class Http(override val status: Int) : AddonFailure("The add-on answered $status")

    /**
     * What answered isn't an add-on: a 4xx such as 404 ([status]), a web page such as a Wi-Fi
     * sign-in, or JSON that isn't a manifest. The address (or the key a personalised one carries) is
     * what's wrong, not something to wait out.
     */
    class NotAnAddon(override val status: Int? = null) : AddonFailure("That isn't an add-on")

    /** What was typed can't be a web address at all, so nothing was asked. */
    class BadAddress : AddonFailure("That isn't a web address") {
        override val status: Int? = null
    }

    /**
     * A film's add-ons were never asked: TMDB, which turns the film into the IMDb id they're asked
     * by, didn't answer. Not the add-ons' fault, so worded apart from [Unreachable].
     */
    class NoLookup(cause: Throwable) : AddonFailure("TMDB didn't answer", cause) {
        override val status: Int? = null
    }
}

/**
 * A thin, read-only client for the open Stremio add-on protocol — the same protocol Stremio and
 * many Kodi add-ons speak. OpenTV ships with no add-ons and hosts no content; this only talks to
 * manifest URLs the user has pasted in themselves.
 *
 * ## What it does
 * - [fetchManifest] fetches an add-on's `manifest.json` and returns its name and what it serves if
 *   it looks like a real add-on (has an id + name), so a pasted address can be checked, and
 *   otherwise throws an [AddonFailure] saying why not.
 * - [streams] asks an add-on for the streams it offers for a title, as it gave them: direct links
 *   (with any request headers it asks for) and torrents. Debrid-backed add-ons resolve a title to a
 *   direct link internally, using the debrid key baked into the user's personalised manifest URL, so
 *   Settee never handles that key. An add-on that can't be asked throws an [AddonFailure], so the
 *   caller can tell that apart from one with nothing.
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
     * Fetch and lightly validate an add-on manifest: its name and what it serves, or an [AddonFailure]
     * when the address doesn't resolve to something that looks like a Stremio manifest (id + name).
     */
    fun fetchManifest(manifestUrl: String): AddonManifest {
        val url = manifestUrl.trim().toHttpUrlOrNull() ?: throw AddonFailure.NotAnAddon()
        val manifest = get(url) as? JsonObject
        val hasId = manifest?.text("id")?.isNotBlank() == true
        val name = manifest?.text("name")?.takeIf { hasId && it.isNotBlank() } ?: throw AddonFailure.NotAnAddon()
        // A resource is a name, or an object with a name and the types it serves when those differ
        // from the add-on's own.
        val resources = (manifest["resources"] as? JsonArray).orEmpty()
        val topTypes = manifest.texts("types")
        val served = resources.mapNotNull { resource ->
            (resource as? JsonPrimitive)?.contentOrNull?.let { it to topTypes }
                ?: (resource as? JsonObject)?.text("name")?.let { name ->
                    // An object naming no types of its own takes the manifest's.
                    name to (if (resource["types"] is JsonArray) resource.texts("types") else topTypes)
                }
        }
        // Each resource's own types, not all of them merged: an add-on whose catalogue lists shows may
        // still only have streams for films, and asking it for an episode only wastes the viewer's wait.
        val streamTypes = served.filter { it.first == STREAM }.flatMap { it.second }
        val types = topTypes + served.flatMap { it.second }
        return AddonManifest(
            name,
            served.map { it.first }.distinctFilled(),
            types.distinctFilled(),
            streamTypes.distinctFilled(),
        )
    }

    /**
     * The streams [addonManifestUrl] offers for a title. [type] is "movie" or "series"; [id] is the
     * IMDb id — `tt1234567` for a film, `tt1234567:1:3` for series season 1 episode 3. Direct links
     * and torrents both come back; what to show and play is `AddonStreams`' business.
     */
    fun streams(addonManifestUrl: String, addonName: String, type: String, id: String): List<StremioStream> {
        // A manifest URL is "<base>/manifest.json"; resource URLs hang off the same base.
        val base = addonManifestUrl.trim().removeSuffix("manifest.json").trimEnd('/')
        val url = "$base/stream/$type/$id.json".toHttpUrlOrNull() ?: throw AddonFailure.NotAnAddon()
        val reply = try {
            get(url)
        } catch (notFound: AddonFailure.NotAnAddon) {
            // Some add-ons answer 404 for a title they have nothing for: an answer, not a failure.
            if (notFound.status == HttpURLConnection.HTTP_NOT_FOUND) return emptyList() else throw notFound
        }
        val streams = (reply as? JsonObject)?.get("streams") as? JsonArray ?: return emptyList()
        return streams.mapNotNull { (it as? JsonObject)?.let { entry -> streamOf(entry, addonName) } }
    }

    /**
     * One entry of a streams reply, or null for one nothing here can play: a YouTube id (`ytId`) or a
     * link into another app (`externalUrl`), which carry neither a `url` nor an `infoHash`.
     */
    private fun streamOf(entry: JsonObject, addonName: String): StremioStream? {
        val hints = entry["behaviorHints"] as? JsonObject
        val link = entry.text("url")?.takeIf { it.isNotBlank() }?.let { url ->
            val request = ((hints?.get("proxyHeaders") as? JsonObject)?.get("request") as? JsonObject).orEmpty()
            StreamLink.Direct(
                url,
                request.mapNotNull { (k, v) ->
                    (v as? JsonPrimitive)?.contentOrNull?.let { k to it }
                }.toMap(),
            )
        } ?: entry.text("infoHash")?.takeIf { it.isNotBlank() }?.let { hash ->
            StreamLink.Torrent(
                infoHash = hash.lowercase(),
                fileIdx = (entry["fileIdx"] as? JsonPrimitive)?.intOrNull,
                sources = entry.texts("sources"),
            )
        } ?: return null
        val name = entry.line("name")
        return StremioStream(
            link = link,
            title = entry.line("title") ?: entry.line("description") ?: name ?: "Stream",
            addonName = addonName,
            name = name,
            bingeGroup = hints?.text("bingeGroup"),
            filename = hints?.text("filename"),
            videoSize = (hints?.get("videoSize") as? JsonPrimitive)?.longOrNull,
        )
    }

    /** [key]'s text on one line, or null when it's missing, blank or not text. */
    private fun JsonObject.line(key: String): String? =
        text(key)?.replace('\n', ' ')?.trim()?.takeIf { it.isNotBlank() }

    /** The JSON at [url], or the [AddonFailure] that stopped it. Never logs the address. */
    private fun get(url: HttpUrl): JsonElement {
        val body = read(url)
        // A Wi-Fi sign-in page or any other web page answers 200 with HTML.
        return try {
            json.parseToJsonElement(body)
        } catch (_: SerializationException) {
            throw AddonFailure.NotAnAddon()
        }
    }

    private fun read(url: HttpUrl): String {
        val request = Request.Builder().url(url).header("User-Agent", "OpenTV").build()
        return try {
            http.newCall(request).execute().use { response ->
                failureOf(response.code)?.let { throw it }
                response.body.string()
            }
        } catch (e: IOException) {
            // An AddonFailure is an IOException too, and passes through as it is.
            throw e as? AddonFailure ?: AddonFailure.Unreachable(e).also {
                Log.w(TAG, "Stremio request failed: ${e.javaClass.simpleName}")
            }
        }
    }

    /** Only a 5xx is the add-on's own trouble; any other refusal says the address isn't right. */
    private fun failureOf(status: Int): AddonFailure? = when (status) {
        in SUCCESS -> null
        in SERVER_ERRORS -> AddonFailure.Http(status)
        else -> AddonFailure.NotAnAddon(status)
    }

    // By hand rather than with jsonPrimitive, which throws on an object or array where text was expected (#364).
    private fun JsonObject.text(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull

    private fun List<String>.distinctFilled() = filter { it.isNotBlank() }.distinct()

    private fun JsonObject.texts(key: String): List<String> =
        (this[key] as? JsonArray).orEmpty().mapNotNull { (it as? JsonPrimitive)?.contentOrNull }

    private companion object {
        const val TAG = "StremioClient"
        const val STREAM = "stream"
        val SUCCESS = 200..299
        val SERVER_ERRORS = 500..599
    }
}
