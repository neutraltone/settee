/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv.core

import android.content.Context
import android.content.res.Resources
import android.provider.Settings
import android.text.format.DateFormat
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

/**
 * Every clock time and day label the viewer sees, as their language writes it. The language, the
 * zone and the 12- or 24-hour choice are each read when a time is drawn rather than captured once:
 * changing the app's language recreates the activity but not the process, the zone follows the
 * device's ([DeviceZone]), and the device's clock setting decides 12 or 24 hours everywhere, whatever
 * the app's language would choose (#645).
 */
class ViewerClock(
    private val locale: () -> Locale,
    private val zone: () -> ZoneId,
    private val is24Hour: () -> Boolean,
) {

    /** [utcMillis] as a clock time, such as `20:00` or `8:00 PM`. */
    fun formatTime(utcMillis: Long): String = format(utcMillis, hours())

    /** [utcMillis]'s weekday, day and month, in the language's own order: `Sat, 4 Jul`, `7月4日(土)`. */
    fun formatDay(utcMillis: Long): String = format(utcMillis, DAY)

    /** [utcMillis]'s day and clock time as one phrase, joined as the language joins them: `Sat, 4 Jul, 20:00`. */
    fun formatDayTime(utcMillis: Long): String = format(utcMillis, DAY + hours())

    /** [utcMillis]'s date with its year, as the language writes it: `4 Jul 2026`, `04.07.2026`. */
    fun formatDate(utcMillis: Long): String = format(utcMillis, DATE)

    private fun hours(): String = if (is24Hour()) "Hm" else "hm"

    private fun format(utcMillis: Long, skeleton: String): String {
        val locale = locale()
        return formatters.getOrPut(skeleton to locale) {
            if (skeleton == DATE) {
                DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM).withLocale(locale)
            } else {
                DateTimeFormatter.ofPattern(DateFormat.getBestDateTimePattern(locale, skeleton), locale)
            }
        }.format(Instant.ofEpochMilli(utcMillis).atZone(zone()))
    }

    companion object {
        // Skeletons, not patterns: each language puts the fields in its own order with its own
        // punctuation, which a fixed "EEE d MMM" would write in English order everywhere.
        private const val DAY = "EEEdMMM"

        // Not a skeleton: the localised medium date, which Test connection has always used.
        private const val DATE = "date:MEDIUM"

        /**
         * The app's language as [LocaleUtils] applies it to [context], the device's clock setting,
         * and [zone] (the graph's [ServiceLocator.Graph.zone]).
         */
        fun of(context: Context, zone: () -> ZoneId): ViewerClock = ViewerClock(
            locale = { LocaleUtils.wrap(context).resources.configuration.locales[0] },
            zone = zone,
            is24Hour = { deviceIs24Hour(context) },
        )

        /**
         * Whether the device's clock is set to 24 hours. With no choice made, it's the device's own
         * language that decides, as Android's clock does: not [DateFormat.is24HourFormat], which
         * falls back to [context]'s language, and every context here carries the app's.
         */
        fun deviceIs24Hour(context: Context): Boolean =
            when (Settings.System.getString(context.contentResolver, Settings.System.TIME_12_24)) {
                "24" -> true
                "12" -> false
                else -> DateFormat.getBestDateTimePattern(Resources.getSystem().configuration.locales[0], "jm")
                    .contains('H')
            }

        // A formatter holds a pattern and a locale but no zone (the zone is the instant's, given on each
        // call), so one per skeleton and locale serves every zone and every clock. Kept rather than built
        // per call because the guide's cells ask on every recomposition; keyed by locale, so a language
        // change finds its own rather than the last one's.
        private val formatters = ConcurrentHashMap<Pair<String, Locale>, DateTimeFormatter>()
    }
}
