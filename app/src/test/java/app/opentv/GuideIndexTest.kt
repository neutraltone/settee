/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv

import app.opentv.data.model.Programme
import app.opentv.ui.guideIndex
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import org.junit.Test

/**
 * The open guide's programme index while a guide sync writes its listings in batches (#257): the
 * first listings show at once, a burst of batches costs one rebuild per quiet period rather than one
 * per batch, and the finished listings always show.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class GuideIndexTest {

    private val quiet = 2_000L

    @Test
    fun `the guide's first listings show straight away, grouped by channel`() = runTest {
        val bbc = programme("bbc1", "News")
        val itv = programme("itv1", "Quiz")
        val seen = collect(MutableStateFlow(WINDOW)) { _, _ -> listingsThenWait(listOf(bbc, itv)) }

        advanceTimeBy(1)

        assertThat(seen).containsExactly(mapOf("bbc1" to listOf(bbc), "itv1" to listOf(itv)) to 0L)
    }

    @Test
    fun `a burst of batches inside the quiet period rebuilds once, then once more when it ends`() = runTest {
        // Ten batches 100 ms apart, as a guide sync writes them; the stored listings grow each time.
        val batches = flow {
            for (count in 1..10) {
                emit(programmes(count))
                if (count < 10) delay(100)
            }
            awaitCancellation()
        }
        val seen = collect(MutableStateFlow(WINDOW)) { _, _ -> batches }

        advanceTimeBy(10_000)

        assertThat(seen.map { it.programmeCount() }).containsExactly(1 to 0L, 10 to quiet).inOrder()
    }

    @Test
    fun `a long sync fills the guide in at most once per quiet period, ending on the finished listings`() = runTest {
        // A batch at 0 ms, then every 500 ms from 300 ms to 4800 ms.
        val batches = flow {
            emit(programmes(1))
            delay(300)
            for (count in 2..11) {
                emit(programmes(count))
                if (count < 11) delay(500)
            }
            awaitCancellation()
        }
        val seen = collect(MutableStateFlow(WINDOW)) { _, _ -> batches }

        advanceTimeBy(20_000)

        // At 2 s the latest batch is the one from 1800 ms (5 stored), at 4 s the one from 3800 ms (9).
        assertThat(seen.map { it.programmeCount() })
            .containsExactly(1 to 0L, 5 to quiet, 9 to 2 * quiet, 11 to 3 * quiet)
            .inOrder()
    }

    @Test
    fun `the same listings written again don't rebuild the guide`() = runTest {
        val rewritten = flow {
            emit(programmes(3))
            delay(100)
            emit(programmes(3))
            delay(5_000)
            emit(programmes(3))
            awaitCancellation()
        }
        val seen = collect(MutableStateFlow(WINDOW)) { _, _ -> rewritten }

        advanceTimeBy(20_000)

        assertThat(seen.map { it.programmeCount() }).containsExactly(3 to 0L)
    }

    @Test
    fun `moving to the next half hour shows its listings straight away, even mid-burst`() = runTest {
        val window = MutableStateFlow(WINDOW)
        val seen = collect(window) { from, _ ->
            if (from == WINDOW.first) {
                flow {
                    emit(programmes(1))
                    delay(100)
                    emit(programmes(2))
                    awaitCancellation()
                }
            } else {
                listingsThenWait(listOf(programme("bbc1", "Later")))
            }
        }

        advanceTimeBy(500)
        window.value = NEXT_WINDOW
        advanceTimeBy(1)

        assertThat(seen.map { it.programmeCount() }).containsExactly(1 to 0L, 1 to 500L).inOrder()
        assertThat(seen.last().first.getValue("bbc1").single().title).isEqualTo("Later")
    }

    /** Collects the index for the test's lifetime, noting each value with the virtual time it arrived. */
    private fun TestScope.collect(
        window: MutableStateFlow<Pair<Long, Long>>,
        source: (Long, Long) -> Flow<List<Programme>>,
    ): List<Pair<Map<String, List<Programme>>, Long>> {
        val seen = mutableListOf<Pair<Map<String, List<Programme>>, Long>>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            window.guideIndex(source, quiet).collect { seen += it to testScheduler.currentTime }
        }
        return seen
    }

    private fun Pair<Map<String, List<Programme>>, Long>.programmeCount() = first.values.sumOf { it.size } to second

    private fun listingsThenWait(programmes: List<Programme>) = flow {
        emit(programmes)
        awaitCancellation()
    }

    /** What the store holds once [count] programmes have been written: a fresh list each time, as Room returns. */
    private fun programmes(count: Int) = (1..count).map { programme("bbc1", "Programme $it", startMinute = it) }

    private fun programme(channel: String, title: String, startMinute: Int = 0) = Programme(
        feedId = 1,
        epgChannelId = channel,
        startUtcMillis = WINDOW.first + startMinute * 60_000L,
        endUtcMillis = WINDOW.first + (startMinute + 1) * 60_000L,
        title = title,
    )

    private companion object {
        val WINDOW = 1_800_000_000_000L to 1_800_086_400_000L
        val NEXT_WINDOW = 1_800_001_800_000L to 1_800_086_400_000L
    }
}
