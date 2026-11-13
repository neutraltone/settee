/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import app.opentv.data.model.Channel
import app.opentv.data.model.Programme
import app.opentv.data.repo.LineupChannel
import app.opentv.ui.ChannelsViewModel
import app.opentv.ui.channels.GuideGrid
import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Moving around a guide whose rows build only the programmes near the screen: the rest of the day
 * isn't built, and the Highlight still reaches every programme, the next row and the next day.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = TV_1080P, application = android.app.Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class GuideScrollingTest {

    @get:Rule
    val compose = createComposeRule()

    @get:Rule
    val artwork = PlaceholderArtwork()

    private fun row(id: Long, programmes: List<Programme>): ChannelsViewModel.Row {
        val channel = Channel(
            id = id,
            sourceId = 1,
            streamId = "$id",
            name = "Channel $id",
            streamUrl = "https://example.com/$id.ts",
            categoryId = null,
            logoUrl = "https://example.com/$id.png",
            epgChannelId = null,
            number = id.toInt(),
        )
        return ChannelsViewModel.Row(LineupChannel(channel, listOf(channel)), programmes = programmes)
    }

    /** Back-to-back programmes from [fromMillis], each [minutes] long in turn, titled "[name] 0", "[name] 1"… */
    private fun programmes(name: String, fromMillis: Long, minutes: List<Int>): List<Programme> {
        var start = fromMillis
        return minutes.mapIndexed { i, length ->
            val end = start + length * MINUTE_MS
            Programme(feedId = 1, epgChannelId = name, startUtcMillis = start, endUtcMillis = end, title = "$name $i")
                .also { start = end }
        }
    }

    private fun halfHours(name: String, dayStart: Long) = programmes(name, dayStart, List(48) { 30 })

    private fun press(key: Key, times: Int = 1) = repeat(times) {
        compose.onRoot().performKeyInput { pressKey(key) }
    }

    private fun programme(title: String) = hasText(title) and hasClickAction()

    private fun builtCells(name: String) =
        compose.onAllNodes(hasText("$name ", substring = true) and hasClickAction()).fetchSemanticsNodes().size

    private fun exists(matcher: SemanticsMatcher) = compose.onAllNodes(matcher).fetchSemanticsNodes().isNotEmpty()

    @Test
    fun `on a later day a row builds only the programmes around the screen, wherever it's scrolled to`() {
        val rows = listOf(
            row(1, halfHours("A", 0)),
            row(2, programmes("H", 0, List(24) { 60 })),
            // A 5-minute programme floored wider than it runs, so the cells after it show they still
            // line up with the times above.
            row(3, programmes("M", 0, List(12) { listOf(5, 25, 90) }.flatten())),
            row(4, programmes("F", 0, listOf(240, 30, 30, 180, 60, 480, 90, 330))),
        )
        compose.setTvContent { GuideGrid(rows = rows, windowStartMillis = 0, onWatch = {}, dayOffset = 1) }

        // The strip shows 740 dp (960 less the channel column), six or seven half hours, not 48.
        // 00:00 to 04:00 are on screen (with the half hour of slack), 04:00 is one past the edge.
        assertThat(builtCells("A")).isAtMost(MOST_CELLS_BUILT)
        assertThat(exists(programme("A 8"))).isTrue()
        assertThat(exists(programme("A 9"))).isFalse()
        assertThat(exists(programme("A 20"))).isFalse()

        // Along to 13:30.
        press(Key.DirectionRight, times = 28)
        compose.onNode(programme("A 27")).assertIsFocused()

        assertThat(builtCells("A")).isAtMost(MOST_CELLS_BUILT)
        assertThat(exists(programme("A 10"))).isFalse()
        assertThat(exists(programme("A 40"))).isFalse()
        captureScreen("guide_later_day")
    }

    @Test
    fun `holding Right from now visits every programme to the end of the day, through one wider than the screen`() {
        val noon = 12 * 60 * MINUTE_MS
        // A four-hour film is 960 dp, wider than the 740 dp of strip on screen.
        val today = programmes("T", noon, listOf(30, 240) + List(15) { 30 })
        val highlighted = mutableListOf<String>()
        var nextDay = 0
        compose.setTvContent {
            GuideGrid(
                rows = listOf(row(1, today)),
                windowStartMillis = noon,
                windowEndMillis = 24 * 60 * MINUTE_MS,
                onWatch = {},
                onHighlight = { _, programme -> programme?.let { highlighted += it.title } },
                onNextDay = { nextDay++ },
                nowMillis = noon + 10 * MINUTE_MS,
            )
        }

        press(Key.DirectionRight, times = today.size)

        compose.runOnIdle {
            assertThat(highlighted.dropConsecutiveRepeats()).containsExactlyElementsIn(today.map { it.title }).inOrder()
            assertThat(nextDay).isEqualTo(0)
        }
        press(Key.DirectionRight)
        compose.runOnIdle { assertThat(nextDay).isEqualTo(1) }
    }

    @Test
    fun `Left before a later day's first programme lands on the previous day's last, at the far end of its strip`() {
        var day by mutableIntStateOf(1)
        var landOnRow by mutableStateOf<Any?>(null)
        compose.setTvContent {
            GuideGrid(
                rows = listOf(row(1, halfHours("D$day", day * DAY_MS))),
                windowStartMillis = day * DAY_MS,
                onWatch = {},
                dayOffset = day,
                onPrevDay = { row ->
                    day -= 1
                    landOnRow = row.key
                },
                landOnRow = landOnRow,
                landAtEnd = landOnRow != null,
                onLanded = { landOnRow = null },
            )
        }

        press(Key.DirectionRight)
        compose.onNode(programme("D1 0")).assertIsFocused()
        press(Key.DirectionLeft)

        compose.onNode(programme("D0 47")).assertIsFocused()
    }

    @Test
    fun `Down from a programme hours along the day lands on the next row's programme at that time`() {
        val rows = listOf(row(1, halfHours("A", 0)), row(2, halfHours("B", 0)))
        compose.setTvContent { GuideGrid(rows = rows, windowStartMillis = 0, onWatch = {}, dayOffset = 1) }

        // Along to 06:00.
        press(Key.DirectionRight, times = 13)
        compose.onNode(programme("A 12")).assertIsFocused()
        press(Key.DirectionDown)

        compose.onNode(programme("B 12")).assertIsFocused()
    }

    private fun List<String>.dropConsecutiveRepeats() = filterIndexed { i, title -> i == 0 || this[i - 1] != title }

    private companion object {
        const val MINUTE_MS = 60_000L
        const val DAY_MS = 24 * 60 * MINUTE_MS

        // Seven half hours on screen, the one the scroll position is part-way into, one past each
        // edge, and the day's first and last, which paging to this day lands on: 12, with one to
        // spare for wherever bringing the Highlight into view leaves the scroll.
        const val MOST_CELLS_BUILT = 13
    }
}
