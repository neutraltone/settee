/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */

/** A key built into the app, named by the secret (and environment variable) it comes from. */
enum class BuiltInKey(val secret: String, internal val expected: String, private val format: Regex) {
    OPENSUBTITLES("OPENSUBTITLES_API_KEY", "32 letters and digits", Regex("[A-Za-z0-9]{32}")),

    /** The v3 API Key, or the v4 Read Access Token: a JWT, three base64url parts. */
    TMDB(
        "TMDB_API_KEY",
        "a v3 API Key (32 hex characters) or a v4 Read Access Token (three dot-separated parts starting eyJ)",
        Regex("[0-9a-f]{32}|eyJ[A-Za-z0-9_-]*\\.[A-Za-z0-9_-]+\\.[A-Za-z0-9_-]+"),
    ),

    /** What Sentry shows as the project's DSN: its public key, the ingest host and the project's number. */
    SENTRY_DSN(
        "SENTRY_DSN",
        "a DSN of the form https://<key>@<host>/<project id>",
        Regex("https://[A-Za-z0-9]+@[A-Za-z0-9.-]+(:[0-9]+)?/[0-9]+"),
    ),
    ;

    internal fun matches(value: String) = format.matches(value)
}

/**
 * [value] as it is built in, or a stopped build when it is set but in the wrong format. Blank builds,
 * leaving that feature out: this Mac, forks and PR CI have no keys, and release.yml refuses a blank
 * one itself. The build
 * used to keep only the characters a key may have, so v1.5.0 shipped a GitHub token, underscore
 * stripped, as its OpenSubtitles key (#628). The message says what the key should look like and
 * never the value, which would land in a CI log.
 */
fun checkedBuiltInKey(key: BuiltInKey, value: String): String {
    if (value.isEmpty()) return value
    require(key.matches(value)) { "${key.secret} is not ${key.expected}. Check the secret." }
    return value
}
