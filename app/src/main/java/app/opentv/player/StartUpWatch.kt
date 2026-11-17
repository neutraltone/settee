/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv.player

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Calls [onLate] when a stream handed to the player hasn't played after [START_UP_LIMIT_MILLIS] (#463).
 * Some panels take the connection and then send nothing. Each attempt waits out the HTTP client's read
 * timeout, [IptvLoadErrorPolicy] retries that, and [PlaybackRecovery] restarts it twice, so with nothing
 * else watching, the viewer looked at the loading animation for about ten minutes. [FirstFrameWatch]
 * can't see it: no data means no decoder, and its clock starts only with one.
 */
internal class StartUpWatch(private val scope: CoroutineScope, private val onLate: () -> Unit) {

    private var clock: Job? = null

    /** Whether a stream is on its way: from when it's handed over until it plays or leaves. */
    private var waiting = false

    /**
     * Whether the player is being held back on purpose (#356), so a stream can't be expected to play.
     * Letting go starts a full limit rather than what was left of it: the viewer has just come back,
     * and is owed as long to watch it start as anyone, and it spares this a clock of its own.
     */
    var held = false
        set(value) {
            if (value == field) return
            field = value
            if (value) {
                stopClock()
            } else if (waiting) {
                startClock()
            }
        }

    /** The stream just handed to the player starts its wait. */
    fun newStream() {
        waiting = true
        if (held) stopClock() else startClock()
    }

    /**
     * The stream has played, so whatever it waits for from now on is buffering, which isn't this
     * limit's business; or it's left, so there's nothing to wait for.
     */
    fun end() {
        waiting = false
        stopClock()
    }

    private fun startClock() {
        clock?.cancel()
        clock = scope.launch {
            delay(START_UP_LIMIT_MILLIS)
            clock = null
            waiting = false
            onLate()
        }
    }

    private fun stopClock() {
        clock?.cancel()
        clock = null
    }

    private companion object {
        /**
         * Longer than one 30 s read timeout, so a panel that answers on its second attempt still
         * plays, and well under the ten minutes it took before.
         */
        const val START_UP_LIMIT_MILLIS = 45_000L
    }
}
