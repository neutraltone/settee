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
    private const val BYTE_ORDER_MARK = "\uFEFF"

    /** What can end a URL's host: its path, query or fragment. */
    private const val HOST_ENDS = "/?#"
    private const val FILM_PATH = "/movie/"
    private const val EPISODE_PATH = "/series/"

    data class Result(
        val channels: List<Channel>,
        /** The guides `url-tvg`/`x-tvg-url` on the #EXTM3U header lists, in order; empty when it lists none. */
        val declaredEpgUrls: List<String>,
        /** Malformed entries. Films and episodes are left out of the channels but not counted here. */
        val skippedEntries: Int,
    )

    fun parse(input: InputStream, sourceId: Long): Result = input.bufferedReader().use { parse(it, sourceId) }

    fun parse(text: String, sourceId: Long): Result = parse(text.reader().buffered(), sourceId)

    fun parse(reader: BufferedReader, sourceId: Long): Result {
        val channels = ArrayList<Channel>()
        val seenStreamIds = HashSet<String>()
        val repeatIds = RepeatIds()
        var declaredEpgUrls = emptyList<String>()
        var skipped = 0

        var pendingName: String? = null
        var pendingAttributes: Map<String, String> = emptyMap()
        var pendingNumber: Int? = null
        // #EXTVLCOPT / #EXTHTTP lines that apply to the next URL.
        var index = 0

        var first = true
        reader.forEachLine { rawLine ->
            // A byte-order mark isn't whitespace to trim(), so a playlist saved with one had its
            // header read as a channel's URL, losing the guide it declares.
            val line = (if (first) rawLine.removePrefix(BYTE_ORDER_MARK) else rawLine).trim()
            first = false
            when {
                line.isEmpty() -> Unit

                line.startsWith("#EXTM3U", ignoreCase = true) -> {
                    val attributes = parseAttributes(line)
                    val listed = attributes["url-tvg"] ?: attributes["x-tvg-url"]
                    if (listed != null) declaredEpgUrls = GuideAddresses.split(listed)
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
                        // Films and episodes are left out before the dedupe and sortIndex, so the
                        // channels' positions stay consecutive.
                        val attributes = pendingAttributes
                        val tvgId = attributes["tvg-id"]?.takeIf { it.isNotBlank() }
                        val streamId = when {
                            tvgId == null -> "url:${stableHash(line)}"
                            "tvg:$tvgId" !in seenStreamIds -> "tvg:$tvgId"
                            else -> repeatIds.next(tvgId, name)
                        }

                        // A URL listed twice with no tvg-id is one channel: keep the first rather
                        // than let the second collide on the unique index and abort the import.
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

        return Result(channels, declaredEpgUrls, skipped)
    }

    /**
     * Ids for the channels listed after the first with the same tvg-id: regional variants
     * ("BBC One London", "BBC One Wales") and playlists that put a placeholder such as `0` on
     * every channel. The first keeps `tvg:<tvg-id>`, the id it always had, because favourites,
     * custom names, hidden flags, order and guide overrides are all keyed by streamId and the
     * repeats, dropped before #500, carry none of those. Keying every channel on tvg-id plus
     * name was rejected for that reason: it would have moved every one of them.
     *
     * Built from the tvg-id and name rather than the URL, so a rotating token doesn't change
     * it, plus the repeat's ordinal when the name repeats too. Hashed under its own prefix so
     * no tvg-id or URL can produce the same id, whatever characters the tvg-id holds.
     */
    private class RepeatIds {
        /** How many repeats of each tvg-id and name have been listed so far. */
        private val seen = HashMap<String, Int>()

        fun next(tvgId: String, name: String): String {
            // A newline can't occur in either part: both come from one line of the playlist.
            val key = "$tvgId\n$name"
            val ordinal = seen.merge(key, 1, Int::plus) ?: 1
            return "tvgrepeat:" + stableHash(if (ordinal == 1) key else "$key\n$ordinal")
        }
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
     * The display name is everything after the first comma outside the quoted attributes, as
     * VLC and Kodi read it: the last comma would cut "News, Weather & Sport" to its tail, and
     * the first comma on the line can sit inside `group-title="News, Sport"`. Falling back to
     * `tvg-name` matters because a fair number of playlists emit `#EXTINF:-1 ...,` with
     * nothing after the comma.
     */
    private fun displayNameOf(line: String, attributes: Map<String, String>): String? {
        val separator = nameSeparatorOf(line)
        val afterComma = if (separator < 0) "" else line.substring(separator + 1).trim()
        return afterComma.takeIf { it.isNotBlank() }
            ?: attributes["tvg-name"]?.takeIf { it.isNotBlank() }
    }

    /**
     * The index of the first comma outside double quotes. A line whose quotes don't balance
     * has none by that reading, so it falls back to the last comma rather than lose the name.
     */
    private fun nameSeparatorOf(line: String): Int {
        var quoted = false
        for (i in line.indices) {
            when (line[i]) {
                '"' -> quoted = !quoted
                ',' -> if (!quoted) return i
            }
        }
        return line.lastIndexOf(',')
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
