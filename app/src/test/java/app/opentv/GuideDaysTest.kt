/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv

import app.opentv.core.ViewerClock
import app.opentv.ui.ChannelsViewModel
import com.google.common.truth.Truth.assertThat
import java.time.LocalDateTime
import java.time.ZoneId
import java.util.Locale
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Around a clock change, the guide's days are calendar days in the viewer's zone (#375): a later day
 * starts at its own local midnight and is labelled with that day, and a day the clocks change is 23
 * or 25 hours long, rather than every page being 24 hours on from today's midnight.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class GuideDaysTest {

    private val london = ZoneId.of("Europe/London")
    private var now = 0L

    @get:Rule
    val testGraph = TestGraph(clock = { now }, zone = MutableStateFlow(london))

    private val graph = testGraph.graph

    private fun localMillis(at: String) = LocalDateTime.parse(at).atZone(london).toInstant().toEpochMilli()

    /** The guide opened at [at] local time, drawn as a screen draws it, so its window keeps up. */
    private fun guideAt(at: String): ChannelsViewModel {
        now = localMillis(at)
        val viewModel = testGraph.viewModel { ChannelsViewModel(testGraph.app, graph) }
        testGraph.launch { viewModel.windowStartMillis.collect {} }
        testGraph.launch { viewModel.windowEndMillis.collect {} }
        return viewModel
    }

    /** The page [days] on from the one showing, as its start and end. */
    private fun ChannelsViewModel.page(days: Int): Pair<Long, Long> {
        nudgeGuideDay(days)
        testGraph.awaitIdle()
        return windowStartMillis.value to windowEndMillis.value
    }

    @Test
    fun `the day before the autumn change, two days on is Monday from midnight to midnight, labelled Monday`() {
        val (start, end) = guideAt("2025-10-25T12:00").page(2)

        assertThat(start).isEqualTo(localMillis("2025-10-27T00:00"))
        assertThat(end).isEqualTo(localMillis("2025-10-28T00:00"))
        assertThat(ViewerClock({ Locale.UK }, { london }, { true }).formatDay(start)).isEqualTo("Mon, 27 Oct")
    }

    @Test
    fun `on the autumn change day, today's page runs to Monday's midnight`() {
        val (_, end) = guideAt("2025-10-26T12:00").page(0)

        assertThat(end).isEqualTo(localMillis("2025-10-27T00:00"))
    }

    @Test
    fun `the day before the spring change, later days start at midnight`() {
        val guide = guideAt("2026-03-28T12:00")
        val (sunday, _) = guide.page(1)
        val (monday, mondayEnds) = guide.page(1)

        assertThat(sunday).isEqualTo(localMillis("2026-03-29T00:00"))
        assertThat(monday).isEqualTo(localMillis("2026-03-30T00:00"))
        assertThat(mondayEnds).isEqualTo(localMillis("2026-03-31T00:00"))
    }
}
