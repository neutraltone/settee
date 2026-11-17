/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv.player

import app.opentv.player.SkipCue.Stretch

/**
 * When Skip intro or Skip recap shows (#518): from where playback enters a [Stretch] for 10 seconds, or
 * until the stretch ends, and again whenever the controls are brought up inside it. It never skips by
 * itself: a wrong timing would jump into the middle of a scene with nobody choosing it.
 *
 * Measured on the playback position rather than the clock, like [NextEpisodeCountdown], so pausing
 * holds the button and a seek is judged by where it lands.
 */
class SkipButton {

    /** The episode's stretches, from [SkipCue.place]; setting them starts afresh. */
    var stretches: List<Stretch> = emptyList()
        set(value) {
            field = value
            entered = null
        }

    /** The stretch playback is in, and where in it the button first came up. */
    private var entered: Pair<Stretch, Long>? = null

    /** The stretch whose button shows at [positionMillis], if any. */
    fun update(positionMillis: Long, controlsShowing: Boolean): Stretch? {
        val inside = stretches.firstOrNull { positionMillis >= it.fromMillis && positionMillis < it.toMillis }
        if (inside == null) {
            entered = null
            return null
        }
        val (stretch, cameAt) = entered?.takeIf { it.first == inside && positionMillis >= it.second }
            ?: (inside to positionMillis)
        entered = stretch to cameAt
        return inside.takeIf { controlsShowing || positionMillis < cameAt + SHOWS_FOR_MILLIS }
    }

    private companion object {
        const val SHOWS_FOR_MILLIS = 10_000L
    }
}
