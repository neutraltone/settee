/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv.data.parser

/**
 * A playlist's `url-tvg` may list several guides, comma-separated, and a source keeps them in its one
 * guide address the same way, so no column has to change. Only a comma that starts another address
 * (`, http://…`) separates two: a plain split would cut a single address such as
 * `xmltv.php?channels=1,2` in pieces, and an address a viewer typed before guides could be listed
 * would stop working.
 */
object GuideAddresses {

    private val SEPARATOR = Regex(""",\s*(?=[A-Za-z][A-Za-z0-9+.-]*://)""")

    /** Each address in [listed], in order, without blanks or repeats. */
    fun split(listed: String?): List<String> = listed.orEmpty()
        .split(SEPARATOR)
        .map { it.trim().trimEnd(',').trim() }
        .filter { it.isNotEmpty() }
        .distinct()

    /** [addresses] as one stored value that [split] gives back; null when there are none. */
    fun join(addresses: List<String>): String? = addresses.takeIf { it.isNotEmpty() }?.joinToString(",")
}
