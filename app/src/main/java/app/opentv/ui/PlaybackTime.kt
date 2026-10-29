/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv.ui

/** A position or length in a player, as m:ss, or h:mm:ss from an hour up. */
fun formatPlaybackTime(millis: Long): String {
    if (millis <= 0) return "0:00"
    val totalSec = millis / MILLIS_PER_SECOND
    val h = totalSec / SECONDS_PER_HOUR
    val m = (totalSec % SECONDS_PER_HOUR) / SECONDS_PER_MINUTE
    val s = totalSec % SECONDS_PER_MINUTE
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
}

private const val MILLIS_PER_SECOND = 1_000
private const val SECONDS_PER_MINUTE = 60
private const val SECONDS_PER_HOUR = 3_600
