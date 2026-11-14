/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv.data.parser

import app.opentv.data.model.Channel
import java.io.BufferedReader
import java.io.InputStream

/**
 * Streaming M3U/M3U8 playlist parser.
 *
 * Deliberately reads line-by-line rather than loading the whole playlist into memory:
 * real provider playlists routinely run to tens of megabytes and 50,000+ entries, and
 * loading that as a single String is a reliable way to OOM a cheap Android TV box.
 *
 * Tolerant by design. A malformed entry is skipped, not fatal — one bad line in a
 * 40,000-line playlist must never cost the user their entire channel list.
 */
object M3uParser {

    private val ATTRIBUTE_REGEX = Regex("""([\w-]+)="([^"]*)"""")
    private const val SCHEME_SEPARATOR = "://"

    /** What can end a URL's host: its path, query or fragment. */
    private const val HOST_ENDS = "/?#"
    private const val FILM_PATH = "/movie/"
    private const val EPISODE_PATH = "/series/"

    data class Result(
        val channels: List<Channel>,
        /** Value of `url-tvg`/`x-tvg-url` on the #EXTM3U header, if the playlist declares one. */
        val declaredEpgUrl: String?,
        /** Malformed entries. Films and episodes are left out of the channels but not counted here. */
        val skippedEntries: Int,
    )

    fun parse(input: InputStream, sourceId: Long): Result = input.bufferedReader().use { parse(it, sourceId) }

    fun parse(text: String, sourceId: Long): Result = parse(text.reader().buffered(), sourceId)

    fun parse(reader: BufferedReader, sourceId: Long): Result {
        val channels = ArrayList<Channel>()
        val seenStreamIds = HashSet<String>()
        var declaredEpgUrl: String? = null
        var skipped = 0

        var pendingName: String? = null
        var pendingAttributes: Map<String, String> = emptyMap()
        var pendingNumber: Int? = null
        // #EXTVLCOPT / #EXTHTTP lines that apply to the next URL.
        var index = 0

        reader.forEachLine { rawLine ->
            val line = rawLine.trim()
            when {
                line.isEmpty() -> Unit

                line.startsWith("#EXTM3U", ignoreCase = true) -> {
                    val attributes = parseAttributes(line)
                    declaredEpgUrl = attributes["url-tvg"]
                        ?: attributes["x-tvg-url"]
                        ?: declaredEpgUrl
                }

                line.startsWith("#EXTINF", ignoreCase = true) -> {
                    pendingAttributes = parseAttributes(line)
                    pendingName = displayNameOf(line, pendingAttributes)
                    pendingNumber = pendingAttributes["tvg-chno"]?.toIntOrNull()
                }

                // Any other directive: ignore, but keep the pending EXTINF alive.
                line.startsWith("#") -> Unit

                else -> {
                    val name = pendingName
                    if (name.isNullOrBlank()) {
                        // A URL with no preceding #EXTINF. Nothing sensible to label it with.
                        skipped++
                    } else if (!isFilmOrEpisode(line)) {
                        val attributes = pendingAttributes
                        val streamId = attributes["tvg-id"]
                            ?.takeIf { it.isNotBlank() }
                            ?.let { "tvg:$it" }
                            ?: "url:${stableHash(line)}"

                        // Providers repeat the same tvg-id across quality variants. Keep the
                        // first and drop later duplicates rather than letting them collide on
                        // the unique index and abort the whole import.
                        if (seenStreamIds.add(streamId)) {
                            channels += channelOf(sourceId, streamId, name, attributes, pendingNumber, line, index++)
                        }
                    }
                    pendingName = null
                    pendingAttributes = emptyMap()
                    pendingNumber = null
                }
            }
        }

        return Result(channels, declaredEpgUrl, skipped)
    }

    @Suppress("LongParameterList") // One entry's parts, as parse has gathered them.
    private fun channelOf(
        sourceId: Long,
        streamId: String,
        name: String,
        attributes: Map<String, String>,
        number: Int?,
        url: String,
        sortIndex: Int,
    ) = Channel(
        sourceId = sourceId,
        streamId = streamId,
        name = name,
        categoryId = attributes["group-title"]?.takeIf { it.isNotBlank() },
        logoUrl = (attributes["tvg-logo"] ?: attributes["logo"])?.takeIf { it.isNotBlank() },
        epgChannelId = attributes["tvg-id"]?.takeIf { it.isNotBlank() },
        number = number,
        streamUrl = url,
        sortIndex = sortIndex,
    )

    /**
     * Xtream's `m3u_plus` playlists list every film (`/movie/…`) and episode (`/series/…`)
     * alongside the live streams: 100k+ entries that would show up as live channels and can run
     * a Fire TV Stick out of memory during a refresh. Only the first path segment is checked,
     * since that's Xtream's layout; a live URL with the word elsewhere is left alone.
     *
     * Walks the string rather than slicing it: this runs once per entry of the very playlists
     * that are short of memory, so it allocates nothing.
     */
    private fun isFilmOrEpisode(url: String): Boolean {
        val start = pathStart(url)
        return start >= 0 &&
            (
                url.regionMatches(start, FILM_PATH, 0, FILM_PATH.length, ignoreCase = true) ||
                    url.regionMatches(start, EPISODE_PATH, 0, EPISODE_PATH.length, ignoreCase = true)
                )
    }

    /** The index of [url]'s first '/' after the host, or -1 when a '?', '#' or the end comes first. */
    private fun pathStart(url: String): Int {
        val schemeEnd = url.indexOf(SCHEME_SEPARATOR)
        var i = if (schemeEnd < 0) 0 else schemeEnd + SCHEME_SEPARATOR.length
        while (i < url.length && url[i] !in HOST_ENDS) i++
        return if (i < url.length && url[i] == '/') i else -1
    }

    /**
     * The display name is whatever follows the last comma on the #EXTINF line. Falling back
     * to `tvg-name` matters because a fair number of playlists emit `#EXTINF:-1 ...,` with
     * nothing after the comma.
     */
    private fun displayNameOf(line: String, attributes: Map<String, String>): String? {
        val afterComma = line.substringAfterLast(',', missingDelimiterValue = "").trim()
        return afterComma.takeIf { it.isNotBlank() }
            ?: attributes["tvg-name"]?.takeIf { it.isNotBlank() }
    }

    private fun parseAttributes(line: String): Map<String, String> = ATTRIBUTE_REGEX.findAll(line).associate { match ->
        match.groupValues[1].lowercase() to match.groupValues[2]
    }

    /** FNV-1a. Stable across processes and platforms, unlike [String.hashCode] guarantees. */
    private fun stableHash(value: String): String {
        var hash = 0xcbf29ce484222325uL.toLong() // FNV-1a 64-bit offset basis
        for (byte in value.encodeToByteArray()) {
            hash = hash xor (byte.toLong() and 0xff)
            hash *= 0x100000001b3L // FNV-1a 64-bit prime
        }
        return java.lang.Long.toHexString(hash)
    }
}
