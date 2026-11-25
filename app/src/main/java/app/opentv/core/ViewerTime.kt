/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv.core

import java.time.Instant
import java.time.ZoneId
import java.time.temporal.ChronoUnit

/**
 * Where a UTC epoch millis becomes the viewer's local day: the guide's days. Everything is stored in
 * UTC; the zone is passed in (the graph's [ServiceLocator.Graph.zone]), never read from the JVM's
 * default here, so it follows the device when it changes and a test can name its own. Drawing a time
 * or a day as words is [ViewerClock]'s.
 */
object ViewerTime {

    /** One day of the guide, as UTC epoch millis: [startUtcMillis] inclusive, [endUtcMillis] exclusive. */
    data class Window(val startUtcMillis: Long, val endUtcMillis: Long)

    /**
     * The guide's day [dayOffset] days from [nowUtcMillis]'s: today from the current half hour, so
     * live is on screen (the local half hour, which in a zone 45 minutes off UTC, such as Kathmandu's,
     * isn't UTC's); a later day from its local midnight. Each ends at the next calendar day's
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

    private const val HALF_HOUR_MINUTES = 30
}
