/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv.diagnostics

/**
 * [text] with every provider address and login taken out, for whatever Diagnostics stores, shows
 * or exports: a report is photographed and passed around, so it must name nothing a provider could
 * be found or logged into by.
 */
fun redact(text: String): String {
    // Logins first, while the name before each is still there to say what it is.
    val withoutLogins = LOGIN.replace(text) { "${it.groupValues[1]}${it.groupValues[2]}$LOGIN_MARK" }
    val withoutMacs = MAC.replace(withoutLogins, MAC_MARK)
    return ADDRESSES.fold(withoutMacs) { acc, pattern -> pattern.replace(acc, ADDRESS) }
}

private const val ADDRESS = "[address]"
private const val LOGIN_MARK = "[login]"
private const val MAC_MARK = "[mac]"

/** What a login's value is called, longest first where one starts another. */
private val LOGIN_NAMES = listOf(
    "username", "password", "passwd", "pass", "user", "login", "token",
    "authorization", "auth", "mac", "sn", "serial", "key",
)

/** A login's name, `=` or `:`, and its value: the value goes. A bearer token goes with its word. */
private val LOGIN = Regex(
    "\\b(" + LOGIN_NAMES.joinToString("|") + """)(\s*[=:]\s*)(?:Bearer\s+)?[^\s&,;]+""",
    RegexOption.IGNORE_CASE,
)

/** A MAC, which a Stalker portal takes as its login. */
private val MAC = Regex("""(?<![\w:-])[0-9A-Fa-f]{2}(?:[:-][0-9A-Fa-f]{2}){5}(?![\w:-])""")

/**
 * What may follow an address: a space, a quote (Android's own "Unable to resolve host" quotes it), a
 * colon before the rest of a message, closing punctuation, a full stop ending the sentence, or the end.
 */
private const val END = """(?=[\s"',;:)]|\.(?!\w)|$)"""

private val URL = Regex("""[a-zA-Z][a-zA-Z0-9+.-]*://[^\s"'<>]+""")

/**
 * A host named without a scheme, as an exception's message gives it. Its last label must be all lower
 * or all upper case and end the name, so a Java class (`java.net.UnknownHostException`) or a MIME type
 * (`application/vnd.apple.mpegurl`) in the same line is left alone.
 */
private val HOST =
    Regex("""(?<![\w./@-])(?:[A-Za-z0-9-]+\.)+(?:[a-z]{2,24}|[A-Z]{2,24})(?::\d{1,5})?(?:/\S*?)?""" + END)

private val IP = Regex("""(?<![\w.])\d{1,3}(?:\.\d{1,3}){3}(?::\d{1,5})?(?:/\S*?)?""" + END)

/** One label and a port, such as a NAS or a box on the local network. */
private val HOST_PORT = Regex("""(?<![\w./@-])[A-Za-z][A-Za-z0-9-]*:\d{2,5}(?:/\S*?)?""" + END)

private val ADDRESSES = listOf(URL, HOST, IP, HOST_PORT)
