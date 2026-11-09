/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv

import app.opentv.ui.player.countDownLivePause
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Test

/** A live pause lasts as long as this device's memory allows, and no longer than the buffer can hold (#182). */
@OptIn(ExperimentalCoroutinesApi::class)
class LivePauseCountdownTest {

    private val full = MutableStateFlow(false)
    private val left = mutableListOf<Long>()

    @Test
    fun `a pause counts down second by second and ends when its time is up`() = runTest {
        countDownLivePause(pauseMillis = 28_000, full) { left += it }

        assertThat(currentTime).isEqualTo(28_000)
        assertThat(left.take(3)).containsExactly(28_000L, 27_000L, 26_000L).inOrder()
        assertThat(left.last()).isEqualTo(0)
    }

    @Test
    fun `a pause ends as soon as the buffer is full, with time still on the countdown`() = runTest {
        val countdown = launch { countDownLivePause(pauseMillis = 120_000, full) { left += it } }
        advanceTimeBy(5_500)

        full.value = true
        runCurrent()

        assertThat(countdown.isCompleted).isTrue()
        assertThat(currentTime).isEqualTo(5_500)
        assertThat(left.last()).isEqualTo(115_000)
    }
}
