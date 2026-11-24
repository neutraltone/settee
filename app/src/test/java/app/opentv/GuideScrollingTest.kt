/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv

import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.requestFocus
import androidx.compose.ui.unit.dp
import app.opentv.data.model.Channel
import app.opentv.data.model.GuideProgramme
import app.opentv.data.repo.LineupChannel
import app.opentv.ui.ChannelsViewModel
import app.opentv.ui.LocalRailReturn
import app.opentv.ui.RailReturn
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

    private fun row(id: Long, programmes: List<GuideProgramme>): ChannelsViewModel.Row {
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
    private fun programmes(name: String, fromMillis: Long, minutes: List<Int>): List<GuideProgramme> {
        var start = fromMillis
        return minutes.mapIndexed { i, length ->
            val end = start + length * MINUTE_MS
            GuideProgramme(epgChannelId = name, startUtcMillis = start, endUtcMillis = end, title = "$name $i")
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

    private val noInformation = hasText("No guide information yet") and hasClickAction()

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
    fun `Right past a last programme ending at midnight holds the Highlight on the channel until the next day comes`() {
        var day by mutableIntStateOf(0)
        var rowsDay by mutableIntStateOf(0)
        var landOnRow by mutableStateOf<Any?>(null)
        compose.setTvContent {
            GuideGrid(
                // The day's rows come after its window, as they do from the guide's index.
                rows = listOf(row(1, halfHours("D$rowsDay", rowsDay * DAY_MS))),
                windowStartMillis = day * DAY_MS,
                onWatch = {},
                dayOffset = day,
                onNextDay = { row ->
                    day += 1
                    landOnRow = row.key
                },
                landOnRow = landOnRow,
                onLanded = { landOnRow = null },
            )
        }
        compose.onNode(programme("D0 47")).requestFocus()

        press(Key.DirectionRight)

        compose.onNode(hasText("Channel 1") and hasClickAction()).assertIsFocused()
        rowsDay = 1
        compose.onNode(programme("D1 0")).assertIsFocused()
        assertThat(day).isEqualTo(1)
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

    @Test
    fun `Down or Up from a programme hours along the day stops on a channel with no guide`() {
        val rows = listOf(row(1, halfHours("A", 0)), row(2, emptyList()), row(3, halfHours("C", 0)))
        compose.setTvContent { GuideGrid(rows = rows, windowStartMillis = 0, onWatch = {}, dayOffset = 1) }

        press(Key.DirectionRight, times = 4)
        compose.onNode(programme("A 3")).assertIsFocused()
        press(Key.DirectionDown)

        compose.onNode(noInformation).assertIsFocused()
        captureScreen("guide_no_information_highlighted")

        press(Key.DirectionDown)
        compose.onNode(programme("C 3")).assertIsFocused()
        press(Key.DirectionUp)
        compose.onNode(noInformation).assertIsFocused()
    }

    @Test
    fun `OK on a channel's No information plays it, and Right pages to the next day`() {
        val watched = mutableListOf<Long>()
        val nextDayFor = mutableListOf<Long>()
        val rows = listOf(row(1, halfHours("A", 0)), row(2, emptyList()))
        compose.setTvContent {
            GuideGrid(
                rows = rows,
                windowStartMillis = 0,
                onWatch = { watched += it.primary.id },
                onNextDay = { nextDayFor += it.primary.id },
            )
        }
        compose.onNode(noInformation).requestFocus()

        press(Key.DirectionCenter)
        press(Key.DirectionRight)

        compose.runOnIdle {
            assertThat(watched).containsExactly(2L)
            assertThat(nextDayFor).containsExactly(2L)
        }
    }

    @Test
    fun `when the window moves past the highlighted programme, the Highlight moves to the one now on`() {
        var windowStart by mutableStateOf(EIGHT_PM)
        val highlighted = mutableListOf<String>()
        compose.setTvContent {
            GuideGrid(
                rows = listOf(row(1, programmes("News", EIGHT_PM, listOf(30, 30)))),
                windowStartMillis = windowStart,
                onWatch = {},
                onHighlight = { _, programme -> highlighted += programme?.title ?: "channel" },
                nowMillis = windowStart,
            )
        }
        compose.onNode(programme("News 0")).requestFocus()

        windowStart = EIGHT_PM + 30 * MINUTE_MS
        compose.waitForIdle()

        compose.onNode(programme("News 1")).assertIsFocused()
        compose.runOnIdle { assertThat(highlighted.last()).isEqualTo("News 1") }
        captureScreen("guide_window_moved_on")
    }

    @Test
    fun `when the window moves past a row's only programme, the Highlight goes to its channel`() {
        var windowStart by mutableStateOf(EIGHT_PM)
        compose.setTvContent {
            GuideGrid(
                // Second, so it isn't the channel the guide's first focusable would be anyway.
                rows = listOf(
                    row(1, programmes("Film", EIGHT_PM, listOf(120))),
                    row(2, programmes("News", EIGHT_PM, listOf(30))),
                ),
                windowStartMillis = windowStart,
                onWatch = {},
                nowMillis = windowStart,
            )
        }
        compose.onNode(programme("News 0")).requestFocus()

        windowStart = EIGHT_PM + 30 * MINUTE_MS
        compose.waitForIdle()

        compose.onNode(hasText("Channel 2") and hasClickAction()).assertIsFocused()
    }

    @Test
    fun `when the window moves past another row's programme, the Highlight stays where it is`() {
        var windowStart by mutableStateOf(EIGHT_PM)
        compose.setTvContent {
            GuideGrid(
                rows = listOf(
                    row(1, programmes("News", EIGHT_PM, listOf(30, 30))),
                    row(2, programmes("Film", EIGHT_PM, listOf(120))),
                ),
                windowStartMillis = windowStart,
                onWatch = {},
                nowMillis = windowStart,
            )
        }
        compose.onNode(programme("Film 0")).requestFocus()

        windowStart = EIGHT_PM + 30 * MINUTE_MS
        compose.waitForIdle()

        compose.onNode(programme("Film 0")).assertIsFocused()
    }

    @Test
    fun `after the window moves past the highlighted programme, coming back from the Rail finds the one now on`() {
        var windowStart by mutableStateOf(EIGHT_PM)
        val rail = RailReturn()
        compose.setTvContent {
            CompositionLocalProvider(LocalRailReturn provides rail) {
                Row {
                    // Stands in for the Rail.
                    Box(Modifier.size(48.dp).testTag("rail").focusable())
                    GuideGrid(
                        rows = listOf(row(1, programmes("News", EIGHT_PM, listOf(30, 30)))),
                        windowStartMillis = windowStart,
                        onWatch = {},
                        nowMillis = windowStart,
                    )
                }
            }
        }
        compose.onNode(programme("News 0")).requestFocus()
        windowStart = EIGHT_PM + 30 * MINUTE_MS
        compose.waitForIdle()

        compose.onNodeWithTag("rail").requestFocus()
        compose.runOnIdle { rail.target?.requestFocus() }

        compose.onNode(programme("News 1")).assertIsFocused()
    }

    @Test
    fun `when the window moves past the programme left for the Rail, coming back from it finds the one now on`() {
        var windowStart by mutableStateOf(EIGHT_PM)
        val rail = RailReturn()
        compose.setTvContent {
            CompositionLocalProvider(LocalRailReturn provides rail) {
                Row {
                    // Stands in for the Rail.
                    Box(Modifier.size(48.dp).testTag("rail").focusable())
                    GuideGrid(
                        rows = listOf(row(1, programmes("News", EIGHT_PM, listOf(30, 30)))),
                        windowStartMillis = windowStart,
                        onWatch = {},
                        nowMillis = windowStart,
                    )
                }
            }
        }
        compose.onNode(programme("News 0")).requestFocus()
        compose.onNodeWithTag("rail").requestFocus()

        windowStart = EIGHT_PM + 30 * MINUTE_MS
        compose.waitForIdle()

        compose.onNodeWithTag("rail").assertIsFocused()
        compose.runOnIdle { rail.target?.requestFocus() }
        compose.onNode(programme("News 1")).assertIsFocused()
    }

    @Test
    fun `after the Highlight falls back to a channel and moves on, later window moves leave it where it is`() {
        var windowStart by mutableStateOf(EIGHT_PM)
        compose.setTvContent {
            GuideGrid(
                rows = listOf(
                    row(1, programmes("Film", EIGHT_PM, listOf(180))),
                    row(2, programmes("News", EIGHT_PM, listOf(30))),
                ),
                windowStartMillis = windowStart,
                onWatch = {},
                nowMillis = windowStart,
            )
        }
        compose.onNode(programme("News 0")).requestFocus()
        windowStart = EIGHT_PM + 30 * MINUTE_MS
        compose.waitForIdle()
        compose.onNode(hasText("Channel 2") and hasClickAction()).assertIsFocused()

        compose.onNode(programme("Film 0")).requestFocus()
        windowStart = EIGHT_PM + 60 * MINUTE_MS
        compose.waitForIdle()

        compose.onNode(programme("Film 0")).assertIsFocused()
    }

    @Test
    fun `a guide refresh that keeps the highlighted programme leaves the Highlight on it`() {
        var rows by mutableStateOf(listOf(row(1, programmes("News", EIGHT_PM, listOf(30, 30)))))
        compose.setTvContent {
            GuideGrid(rows = rows, windowStartMillis = EIGHT_PM, onWatch = {}, nowMillis = EIGHT_PM)
        }
        compose.onNode(programme("News 1")).requestFocus()

        // The same programmes again, as a refresh brings them: new objects, the same starts.
        rows = listOf(row(1, programmes("News", EIGHT_PM, listOf(30, 30))))
        compose.waitForIdle()

        compose.onNode(programme("News 1")).assertIsFocused()
    }

    private fun List<String>.dropConsecutiveRepeats() = filterIndexed { i, title -> i == 0 || this[i - 1] != title }

    private companion object {
        const val MINUTE_MS = 60_000L
        const val DAY_MS = 24 * 60 * MINUTE_MS
        const val EIGHT_PM = 20 * 60 * MINUTE_MS

        // Seven half hours on screen, the one the scroll position is part-way into, one past each
        // edge, and the day's first and last, which paging to this day lands on: 12, with one to
        // spare for wherever bringing the Highlight into view leaves the scroll.
        const val MOST_CELLS_BUILT = 13
    }
}
