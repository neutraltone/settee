/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv

import app.opentv.core.SleepTimer
import com.google.common.truth.Truth.assertThat
import org.junit.After
import org.junit.Test

class SleepTimerTest {

    // A process-wide singleton: leave it off for whichever test runs next.
    @After
    fun reset() = SleepTimer.clear()

    @Test
    fun `an armed timer counts its minutes down, rounding up`() {
        SleepTimer.armMinutes(30)
        val deadline = SleepTimer.deadline.value!!

        assertThat(SleepTimer.minutesRemaining(deadline - 30 * 60_000L)).isEqualTo(30)
        assertThat(SleepTimer.minutesRemaining(deadline - 29 * 60_000L)).isEqualTo(29)
        assertThat(SleepTimer.minutesRemaining(deadline - 1)).isEqualTo(1)
    }

    @Test
    fun `a timer whose time has run out reads as off, not 0 minutes`() {
        SleepTimer.armMinutes(15)
        val deadline = SleepTimer.deadline.value!!

        assertThat(SleepTimer.minutesRemaining(deadline)).isNull()
        assertThat(SleepTimer.minutesRemaining(deadline + 60_000L)).isNull()
    }

    @Test
    fun `a timer that was never armed reads as off`() {
        assertThat(SleepTimer.minutesRemaining()).isNull()
    }
}
