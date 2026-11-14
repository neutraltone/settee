/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv

import app.opentv.data.repo.settledReads
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.FlowCollector
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
 * always show, and listings rewritten unchanged show nothing new. The read itself runs only when
 * listings are sent (#328): a sync's batches signal a change, they don't each run the guide's query.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SettledListingsTest {

    private val quiet = 2_000L

    /** How many programmes the store holds now. */
    private var stored = 0

    /** How many times the listings were read from the store. */
    private var reads = 0

    @Test
    fun `the first listings come through straight away`() = runTest {
        val seen = collect(
            flow {
                write(3)
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
                    write(count)
                    if (count < 10) delay(100)
                }
                awaitCancellation()
            },
        )

        advanceTimeBy(10_000)

        assertThat(seen).containsExactly(1 to 0L, 10 to quiet).inOrder()
    }

    @Test
    fun `a burst of batches inside the quiet period reads the listings once, then once more when it ends`() = runTest {
        collect(
            flow {
                for (count in 1..10) {
                    write(count)
                    if (count < 10) delay(100)
                }
                awaitCancellation()
            },
        )

        advanceTimeBy(10_000)

        assertThat(reads).isEqualTo(2)
    }

    @Test
    fun `a long sync comes through at most once per quiet period, ending on the finished listings`() = runTest {
        // A batch at 0 ms, then every 500 ms from 300 ms to 4800 ms.
        val seen = collect(
            flow {
                write(1)
                delay(300)
                for (count in 2..11) {
                    write(count)
                    if (count < 11) delay(500)
                }
                awaitCancellation()
            },
        )

        advanceTimeBy(20_000)

        // At 2 s the latest batch is the one from 1800 ms (5 stored), at 4 s the one from 3800 ms (9).
        assertThat(seen).containsExactly(1 to 0L, 5 to quiet, 9 to 2 * quiet, 11 to 3 * quiet).inOrder()
        assertThat(reads).isEqualTo(4)
    }

    @Test
    fun `the same listings written again don't come through again`() = runTest {
        val seen = collect(
            flow {
                write(3)
                delay(100)
                write(3)
                delay(5_000)
                write(3)
                awaitCancellation()
            },
        )

        advanceTimeBy(20_000)

        assertThat(seen).containsExactly(3 to 0L)
    }

    /** Stores [count] programmes and signals the change, as Room's invalidation tracker does for a batch. */
    private suspend fun FlowCollector<Unit>.write(count: Int) {
        stored = count
        emit(Unit)
    }

    /** How many listings each value held, with the virtual time it arrived. */
    private fun TestScope.collect(changes: Flow<Unit>): List<Pair<Int, Long>> {
        val seen = mutableListOf<Pair<Int, Long>>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            changes.settledReads(quiet) { read() }.collect { seen += it.size to testScheduler.currentTime }
        }
        return seen
    }

    /** What the store holds: a fresh list each time, as Room returns. */
    private fun read(): List<String> {
        reads++
        return (1..stored).map { "Programme $it" }
    }
}
