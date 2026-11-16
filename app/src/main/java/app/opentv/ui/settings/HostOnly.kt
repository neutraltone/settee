/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv.ui.settings

import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/**
 * Host only: the full URL carries the login and has no place on a settings list. Empty when no
 * host can be found, never the address itself.
 *
 * M3U addresses are stored as typed, so `java.net.URI` meets ones it rejects (a `|User-Agent=…`
 * suffix, a space) or parses without a host (an underscore in it). It still goes first, so the
 * addresses it handles show as they always have; OkHttp takes most of the rest, and whatever both
 * refuse has its host taken out by hand.
 */
internal fun hostOnly(url: String): String {
    val address = url.trim()
    return runCatching { java.net.URI(address).host }.getOrNull()
        ?: address.toHttpUrlOrNull()?.host
        ?: hostByHand(address)
}

/**
 * After any scheme and `user:pass@`, before the path, query, fragment, `|` suffix or port; empty
 * unless what's left could be a host. The login is cut off before the query, fragment and suffix,
 * since a `#` or `?` in a password would otherwise cut it short and leave the username showing.
 */
private fun hostByHand(address: String): String {
    val authority = address.substringAfter("://").substringBefore('/').substringAfterLast('@')
        .takeWhile { it !in "?#|" }
    if (authority.startsWith('[')) {
        val host = authority.substringBefore(']', missingDelimiterValue = "")
        return if (host.length > 1 && host.drop(1).all { it.isLetterOrDigit() || it in ":." }) "$host]" else ""
    }
    val host = authority.substringBefore(':')
    val port = authority.substringAfter(':', missingDelimiterValue = "")
    val looksLikeHost = host.isNotEmpty() && host.all { it.isLetterOrDigit() || it in "._-" }
    return if (looksLikeHost && port.all(Char::isDigit)) host else ""
}
