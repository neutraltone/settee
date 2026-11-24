/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv.core

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.Locale

/**
 * The only place a UTC epoch millis becomes the viewer's local day or clock time. Everything is
 * stored in UTC; the zone is passed in (the graph's [ServiceLocator.Graph.zone]), never read from the
 * JVM's default here, so it follows the device when it changes and a test can name its own.
 */
object ViewerTime {

    /** One day of the guide, as UTC epoch millis: [startUtcMillis] inclusive, [endUtcMillis] exclusive. */
    data class Window(val startUtcMillis: Long, val endUtcMillis: Long)

    /**
     * The guide's day [dayOffset] days from [nowUtcMillis]'s: today from the current half hour, so
     * live is on screen; a later day from its local midnight. Each ends at the next calendar day's
     * local midnight, stepped as dates rather than 24 hours, which a day the clocks change isn't.
     */
    fun guideWindow(nowUtcMillis: Long, dayOffset: Int, zone: ZoneId): Window {
        val now = Instant.ofEpochMilli(nowUtcMillis).atZone(zone)
        val day = dayOffset.coerceAtLeast(0).toLong()
        val start = if (day == 0L) {
            now.truncatedTo(ChronoUnit.MINUTES).withMinute(now.minute - now.minute % HALF_HOUR_MINUTES)
        } else {
            now.toLocalDate().plusDays(day).atStartOfDay(zone)
        }
        val end = now.toLocalDate().plusDays(day + 1).atStartOfDay(zone)
        return Window(start.toInstant().toEpochMilli(), end.toInstant().toEpochMilli())
    }

    // Built on each call rather than held: a formatter fixes its zone and locale when it's made, so
    // one captured at the top of a file goes on drawing the old zone after the device's changes.

    /** [utcMillis] as a clock time in [zone], such as `21:30`. */
    fun clockTime(utcMillis: Long, zone: ZoneId): String = format("HH:mm", utcMillis, zone)

    /** [utcMillis]'s day in [zone], such as `Sat 4 Jul`. */
    fun dayLabel(utcMillis: Long, zone: ZoneId): String = format("EEE d MMM", utcMillis, zone)

    private fun format(pattern: String, utcMillis: Long, zone: ZoneId): String =
        DateTimeFormatter.ofPattern(pattern, Locale.getDefault()).format(Instant.ofEpochMilli(utcMillis).atZone(zone))

    private const val HALF_HOUR_MINUTES = 30
}
