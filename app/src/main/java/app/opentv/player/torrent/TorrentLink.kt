/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv.player.torrent

import app.opentv.data.model.StreamLink
import java.net.URLDecoder
import java.net.URLEncoder
import java.util.Locale

/*
 * A torrent as the player's key carries it (#670): a magnet link, with the file to play as BEP 53's
 * `so` (select only). A link, rather than fields of its own on the key, so a torrent travels through
 * a route and the player's key exactly as an add-on's web link does, and the player reaches the
 * engine by the link's scheme, as it reaches a NAS by `smb://`.
 */

/** The magnet link the player is handed for this torrent. */
val TorrentRequest.uri: String
    get() = buildString {
        append(MAGNET_PREFIX).append(infoHash.lowercase(Locale.ROOT))
        fileIndex?.let { append("&so=").append(it) }
        trackers.forEach { append("&tr=").append(URLEncoder.encode(it, "UTF-8")) }
    }

/** Whether [uri] is a torrent's link, played through the engine rather than fetched. */
fun isTorrentUri(uri: String): Boolean = uri.startsWith(MAGNET_PREFIX, ignoreCase = true)

/** The torrent [uri] names, or null when it isn't a torrent link [TorrentRequest.uri] made. */
fun torrentRequestOf(uri: String): TorrentRequest? {
    val params = uri.takeIf(::isTorrentUri)?.substringAfter('?')?.split('&').orEmpty().mapNotNull { pair ->
        val name = pair.substringBefore('=', missingDelimiterValue = "")
        if (name.isEmpty()) null else name to URLDecoder.decode(pair.substringAfter('='), "UTF-8")
    }
    val hash = params.firstOrNull { it.first == "xt" }?.second?.removePrefix(BTIH)
    val file = params.firstOrNull { it.first == "so" }?.second?.toIntOrNull()
    val trackers = params.filter { it.first == "tr" }.map { it.second }
    return hash?.let { runCatching { TorrentRequest(it, file, trackers) }.getOrNull() }
}

/**
 * What the engine is asked for an add-on's torrent, or null when its info hash isn't one. Of its
 * sources, only `tracker:` entries are trackers; `dht:` ones name the DHT, which is always on.
 */
fun StreamLink.Torrent.toRequest(): TorrentRequest? = runCatching {
    TorrentRequest(
        infoHash,
        fileIdx,
        sources.filter { it.startsWith(TRACKER) }.map { it.removePrefix(TRACKER) },
    )
}.getOrNull()

private const val BTIH = "urn:btih:"
private const val MAGNET_PREFIX = "magnet:?xt=$BTIH"
private const val TRACKER = "tracker:"
