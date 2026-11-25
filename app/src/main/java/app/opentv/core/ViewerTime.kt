/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv.core

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.time.temporal.ChronoUnit
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

/**
 * The only place a UTC epoch millis becomes the viewer's local day or clock time. Everything is
 * stored in UTC; the zone is passed in (the graph's [ServiceLocator.Graph.zone]), never read from the
 * JVM's default here, so it follows the device when it changes and a test can name its own. The locale
 * defaults to the JVM's; wording that follows the app's own language passes that instead.
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

    /** [utcMillis] as a clock time in [zone], such as `21:30`. */
    fun clockTime(utcMillis: Long, zone: ZoneId, locale: Locale = Locale.getDefault()): String =
        format(utcMillis, zone, locale, "HH:mm") { DateTimeFormatter.ofPattern(it, locale) }

    /** [utcMillis]'s day in [zone], such as `Sat 4 Jul`. */
    fun dayLabel(utcMillis: Long, zone: ZoneId, locale: Locale = Locale.getDefault()): String =
        format(utcMillis, zone, locale, "EEE d MMM") { DateTimeFormatter.ofPattern(it, locale) }

    /** [utcMillis] as [locale] writes a short clock time, `21:30` or `9:30 PM`, in [zone]. */
    fun localTime(utcMillis: Long, zone: ZoneId, locale: Locale = Locale.getDefault()): String =
        format(utcMillis, zone, locale, "time:SHORT") { DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT) }

    /** [utcMillis]'s date as [locale] writes it, such as `4 Jul 2026`, in [zone]. */
    fun localDate(utcMillis: Long, zone: ZoneId, locale: Locale = Locale.getDefault()): String =
        format(utcMillis, zone, locale, "date:MEDIUM") { DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM) }

    // A formatter holds a pattern and a locale but no zone (the zone is the instant's, given on each
    // call), so one per pattern and locale serves every zone. Kept rather than built per call because
    // the guide's cells ask on every recomposition.
    private val formatters = ConcurrentHashMap<Pair<String, Locale>, DateTimeFormatter>()

    private fun format(
        utcMillis: Long,
        zone: ZoneId,
        locale: Locale,
        pattern: String,
        build: (String) -> DateTimeFormatter,
    ): String = formatters.getOrPut(pattern to locale) { build(pattern).withLocale(locale) }
        .format(Instant.ofEpochMilli(utcMillis).atZone(zone))

    private const val HALF_HOUR_MINUTES = 30
}
