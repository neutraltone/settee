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
    fun formatTime(utcMillis: Long): String = format(utcMillis) { bestPattern(hours(), it) }

    /** [utcMillis]'s weekday, day and month, in the language's own order: `Sat, 4 Jul`, `7月4日(土)`. */
    fun formatDay(utcMillis: Long): String = format(utcMillis) { bestPattern(DAY, it) }

    /** [utcMillis]'s day and clock time as one phrase, joined as the language joins them: `Sat, 4 Jul, 20:00`. */
    fun formatDayTime(utcMillis: Long): String = format(utcMillis) { bestPattern(DAY + hours(), it) }

    /** [utcMillis]'s date with its year, as the language writes it: `4 Jul 2026`, `04.07.2026`. */
    fun formatDate(utcMillis: Long): String =
        // The localised medium date, which Test connection has always used, rather than a skeleton.
        format(utcMillis) { DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM).withLocale(it) }

    private fun hours(): String = if (is24Hour()) "Hm" else "hm"

    // Built for each time rather than kept, so nothing outlives a language change: a formatter costs
    // a pattern parse, and the pattern lookup behind it is cached by Android's ICU (#645).
    private fun format(utcMillis: Long, formatter: (Locale) -> DateTimeFormatter): String =
        formatter(locale()).format(Instant.ofEpochMilli(utcMillis).atZone(zone()))

    private fun bestPattern(skeleton: String, locale: Locale): DateTimeFormatter =
        DateTimeFormatter.ofPattern(DateFormat.getBestDateTimePattern(locale, skeleton), locale)

    companion object {
        // Skeletons, not patterns: each language puts the fields in its own order with its own
        // punctuation, which a fixed "EEE d MMM" would write in English order everywhere.
        private const val DAY = "EEEdMMM"

        /**
         * The clock for words outside a screen, such as an error's sentence: the app's language as
         * [LocaleUtils] applies it to [context], the device's clock setting, and [zone] (the graph's
         * [ServiceLocator.Graph.zone]), each read when a time is drawn. A screen's clock is
         * [app.opentv.ui.viewerClock], which reads the clock setting once per configuration.
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
    }
}
