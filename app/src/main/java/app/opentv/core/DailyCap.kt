/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv.core

/**
 * The most reports that aren't crashes or freezes one install sends a day (ADR 0010): handled
 * errors, Slow Journeys and Error Reports together, so one struggling stick can't use up Sentry's
 * free plan. Crashes and freezes are never capped by Settee; Sentry's own rate limit still applies.
 *
 * Counted in [AppSettings], so a restart doesn't start the day again, by UTC day on [now]'s clock:
 * a day is the same length everywhere, and the reset needn't be the viewer's midnight.
 */
class DailyCap(val most: Int = MOST_A_DAY, private val now: () -> Long = System::currentTimeMillis) {
    /** Whether one more report may go today, counting it if so. */
    @Synchronized
    internal fun take(settings: AppSettings): Boolean {
        val day = now() / DAY_MILLIS
        val sent = settings.crashReportsCappedOn(day)
        if (sent >= most) return false
        settings.setCrashReportsCappedOn(day, sent + 1)
        return true
    }

    companion object {
        /** In step with ADR 0010's daily cap. */
        const val MOST_A_DAY = 20
        private const val DAY_MILLIS = 24 * 60 * 60 * 1000L
    }
}
