/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv

import app.opentv.data.repo.settled
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import org.junit.Test

/**
 * Stored listings while a guide sync writes them in batches (#257): the first show at once, a burst
 * of batches costs one read per quiet period rather than one per batch, the finished listings
 * always show, and listings rewritten unchanged show nothing new.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SettledListingsTest {

    private val quiet = 2_000L

    @Test
    fun `the first listings come through straight away`() = runTest {
        val seen = collect(
            flow {
                emit(stored(3))
                awaitCancellation()
            },
        )

        advanceTimeBy(1)

        assertThat(seen).containsExactly(3 to 0L)
    }

    @Test
    fun `a burst of batches inside the quiet period comes through once, then once more when it ends`() = runTest {
        // Ten batches 100 ms apart, as a guide sync writes them; the stored listings grow each time.
        val seen = collect(
            flow {
                for (count in 1..10) {
                    emit(stored(count))
                    if (count < 10) delay(100)
                }
                awaitCancellation()
            },
        )

        advanceTimeBy(10_000)

        assertThat(seen).containsExactly(1 to 0L, 10 to quiet).inOrder()
    }

    @Test
    fun `a long sync comes through at most once per quiet period, ending on the finished listings`() = runTest {
        // A batch at 0 ms, then every 500 ms from 300 ms to 4800 ms.
        val seen = collect(
            flow {
                emit(stored(1))
                delay(300)
                for (count in 2..11) {
                    emit(stored(count))
                    if (count < 11) delay(500)
                }
                awaitCancellation()
            },
        )

        advanceTimeBy(20_000)

        // At 2 s the latest batch is the one from 1800 ms (5 stored), at 4 s the one from 3800 ms (9).
        assertThat(seen).containsExactly(1 to 0L, 5 to quiet, 9 to 2 * quiet, 11 to 3 * quiet).inOrder()
    }

    @Test
    fun `the same listings written again don't come through again`() = runTest {
        val seen = collect(
            flow {
                emit(stored(3))
                delay(100)
                emit(stored(3))
                delay(5_000)
                emit(stored(3))
                awaitCancellation()
            },
        )

        advanceTimeBy(20_000)

        assertThat(seen).containsExactly(3 to 0L)
    }

    /** How many listings each value held, with the virtual time it arrived. */
    private fun TestScope.collect(listings: Flow<List<String>>): List<Pair<Int, Long>> {
        val seen = mutableListOf<Pair<Int, Long>>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            listings.settled(quiet).collect { seen += it.size to testScheduler.currentTime }
        }
        return seen
    }

    /** What the store holds once [count] programmes have been written: a fresh list each time, as Room returns. */
    private fun stored(count: Int) = (1..count).map { "Programme $it" }
}
