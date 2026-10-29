/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv.data.parser

import java.time.LocalDate
import java.time.ZoneOffset

private val LEADING_DATE = Regex("""^(\d{4})-(\d{2})-(\d{2})""")

/**
 * A provider's or TMDB's release or air date (`2024-03-15`, often with a time after it) as UTC
 * epoch millis at midnight that day, or null when there's no full date. A bare year is null too:
 * every title from one year would tie, which is no use to a row sorted by how recent it is.
 */
fun parseReleaseDate(raw: String?): Long? {
    val (year, month, day) = LEADING_DATE.find(raw?.trim().orEmpty())?.destructured ?: return null
    return runCatching {
        LocalDate.of(year.toInt(), month.toInt(), day.toInt()).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
    }.getOrNull()
}
