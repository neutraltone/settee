/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv

import app.opentv.data.model.GuideProgramme
import app.opentv.ui.ProgrammeIndex
import app.opentv.ui.guideBuilds
import app.opentv.ui.guideIndex
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import org.junit.Test

/**
 * The open guide's programme index and the row builds made from it while a guide sync writes its
 * listings (#257): the first listings build at once, new channels and a new window never wait, and
 * more listings for the same window wait for a pause after the last build has finished.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class GuideIndexTest {

    private val quiet = 2_000L

    @Test
    fun `the guide's listings are grouped by channel, for the window they were read for`() = runTest {
        val bbc = programme("bbc1", "News")
        val itv = programme("itv1", "Quiz")
        val seen = mutableListOf<ProgrammeIndex>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            MutableStateFlow(WINDOW).guideIndex { _, _ -> listingsThenWait(listOf(bbc, itv)) }.collect { seen += it }
        }

        advanceTimeBy(1)

        assertThat(seen.single().window).isEqualTo(WINDOW)
        assertThat(seen.single().byChannel).isEqualTo(mapOf("bbc1" to listOf(bbc), "itv1" to listOf(itv)))
    }

    @Test
    fun `moving to the next half hour reads its listings straight away`() = runTest {
        val window = MutableStateFlow(WINDOW)
        val seen = mutableListOf<Pair<ProgrammeIndex, Long>>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            window.guideIndex { from, _ ->
                listingsThenWait(listOf(programme("bbc1", if (from == WINDOW.first) "Now" else "Later")))
            }.collect { seen += it to testScheduler.currentTime }
        }

        advanceTimeBy(500)
        window.value = NEXT_WINDOW
        advanceTimeBy(1)

        assertThat(seen.map { it.first.window to it.second })
            .containsExactly(WINDOW to 0L, NEXT_WINDOW to 500L)
            .inOrder()
        assertThat(seen.last().first.byChannel.getValue("bbc1").single().title).isEqualTo("Later")
    }

    @Test
    fun `the guide's first rows are built at once`() = runTest {
        val builds = builds(flowOf(listOf("BBC One")), MutableStateFlow(index(WINDOW, 3)))

        advanceTimeBy(1)

        assertThat(builds).containsExactly(Build(listOf("BBC One"), 3, 0L))
    }

    @Test
    fun `more listings wait for a pause after a slow build has finished, then build from the latest`() = runTest {
        val index = MutableStateFlow(index(WINDOW, 1))
        // Each build takes 3 s, longer than the pause, as "All channels" does for a big provider.
        val builds = builds(flowOf(listOf("BBC One")), index, buildMillis = 3_000)

        advanceTimeBy(100)
        index.value = index(WINDOW, 2)
        advanceTimeBy(100)
        index.value = index(WINDOW, 3)
        advanceTimeBy(20_000)

        // The first build ends at 3 s; the next waits out the pause after it, to 5 s.
        assertThat(builds).containsExactly(
            Build(listOf("BBC One"), 1, 0L),
            Build(listOf("BBC One"), 3, 3_000 + quiet),
        ).inOrder()
    }

    @Test
    fun `new channels build at once, with the latest listings, even during the pause`() = runTest {
        val channels = MutableStateFlow(listOf("BBC One"))
        val index = MutableStateFlow(index(WINDOW, 1))
        val builds = builds(channels, index)

        advanceTimeBy(500)
        index.value = index(WINDOW, 2)
        advanceTimeBy(500)
        channels.value = listOf("BBC One", "ITV")
        advanceTimeBy(20_000)

        assertThat(builds).containsExactly(
            Build(listOf("BBC One"), 1, 0L),
            Build(listOf("BBC One", "ITV"), 2, 1_000L),
        ).inOrder()
    }

    @Test
    fun `the first listings for the guide and for each new window build at once`() = runTest {
        // Before the first listings are read, the guide builds its channels with none.
        val index = MutableStateFlow(ProgrammeIndex.Empty)
        val builds = builds(flowOf(listOf("BBC One")), index)

        advanceTimeBy(100)
        index.value = index(WINDOW, 3)
        advanceTimeBy(100)
        index.value = index(WINDOW, 4)
        advanceTimeBy(2_300)
        index.value = index(NEXT_WINDOW, 1)
        advanceTimeBy(20_000)

        assertThat(builds.map { it.programmes to it.atMillis }).containsExactly(
            0 to 0L,
            3 to 100L,
            4 to 100 + quiet,
            1 to 2_500L,
        ).inOrder()
    }

    private data class Build(val channels: List<String>, val programmes: Int, val atMillis: Long)

    /** Every build guideBuilds starts, with the virtual time it started. */
    private fun TestScope.builds(
        channels: Flow<List<String>>,
        index: Flow<ProgrammeIndex>,
        buildMillis: Long = 0,
    ): List<Build> {
        val builds = mutableListOf<Build>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            channels.guideBuilds(index, quiet) { names, built ->
                builds += Build(names, built.byChannel.values.sumOf { it.size }, testScheduler.currentTime)
                delay(buildMillis)
            }.collect {}
        }
        return builds
    }

    private fun index(window: Pair<Long, Long>, count: Int) =
        ProgrammeIndex(window, mapOf("bbc1" to (1..count).map { programme("bbc1", "Programme $it", startMinute = it) }))

    private fun listingsThenWait(programmes: List<GuideProgramme>) = flow {
        emit(programmes)
        awaitCancellation()
    }

    private fun programme(channel: String, title: String, startMinute: Int = 0) = GuideProgramme(
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
