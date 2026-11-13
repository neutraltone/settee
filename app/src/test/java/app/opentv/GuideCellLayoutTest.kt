/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv

import androidx.compose.ui.unit.dp
import app.opentv.data.model.Programme
import app.opentv.ui.channels.CellSpan
import app.opentv.ui.channels.cellsToBuild
import app.opentv.ui.channels.layOutCells
import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * Where each guide cell sits along its row (4 dp a minute, short ones floored at 72 dp), and which
 * of them a row builds for the part of the strip on screen.
 */
class GuideCellLayoutTest {

    private fun programme(fromMinute: Int, toMinute: Int) = Programme(
        feedId = 1,
        epgChannelId = "c",
        startUtcMillis = fromMinute * MINUTE_MS,
        endUtcMillis = toMinute * MINUTE_MS,
        title = "$fromMinute-$toMinute",
    )

    @Test
    fun `a short programme is floored at 72 dp and the next gap pays the width back, so later cells keep their time`() {
        val spans = layOutCells(
            listOf(
                programme(0, 60),
                // 5 minutes is 20 dp: drawn at 72, borrowing 52 dp.
                programme(60, 65),
                programme(65, 90),
                // The half-hour gap before this repays the 52 dp, so it still starts at 120 minutes.
                programme(120, 150),
            ),
            windowStartMillis = 0,
            windowEndMillis = DAY_MS,
        )

        assertThat(spans).containsExactly(
            CellSpan(x = 0.dp, width = 240.dp),
            CellSpan(x = 240.dp, width = 72.dp),
            CellSpan(x = 312.dp, width = 100.dp),
            CellSpan(x = 480.dp, width = 120.dp),
        ).inOrder()
    }

    @Test
    fun `a gap before the first programme pushes it along, and one started before the window is cut at its start`() {
        val started = layOutCells(listOf(programme(0, 90), programme(90, 120)), 60 * MINUTE_MS, DAY_MS)
        val gapFirst = layOutCells(listOf(programme(30, 60)), 0, DAY_MS)

        assertThat(started).containsExactly(CellSpan(0.dp, 120.dp), CellSpan(120.dp, 120.dp)).inOrder()
        assertThat(gapFirst).containsExactly(CellSpan(120.dp, 120.dp))
    }

    /** Half-hour cells, 120 dp each, from the start of the strip. */
    private val halfHours = List(48) { CellSpan(x = (it * 120).dp, width = 120.dp) }

    @Test
    fun `a row builds the cells on screen and one more past each edge`() {
        // On screen from 300 to 700 dp: cells 2 (240 to 360) to 5 (600 to 720).
        assertThat(cellsToBuild(halfHours, from = 300.dp, viewport = 400.dp)).isEqualTo(1..6)
    }

    @Test
    fun `at the start of the strip there's no cell before the first to build`() {
        assertThat(cellsToBuild(halfHours, from = 0.dp, viewport = 250.dp)).isEqualTo(0..3)
    }

    @Test
    fun `at the end of the strip there's no cell after the last to build`() {
        assertThat(cellsToBuild(halfHours, from = 5400.dp, viewport = 400.dp)).isEqualTo(44..47)
    }

    @Test
    fun `a programme wider than the screen still has its neighbours built, however far off screen they start`() {
        val film = listOf(
            CellSpan(0.dp, 120.dp),
            CellSpan(120.dp, 960.dp),
            CellSpan(1080.dp, 120.dp),
            CellSpan(1200.dp, 120.dp),
        )

        assertThat(cellsToBuild(film, from = 200.dp, viewport = 400.dp)).isEqualTo(0..2)
    }

    @Test
    fun `scrolled into a gap with nothing on screen, the cells either side of it are built`() {
        val gap = listOf(CellSpan(0.dp, 120.dp), CellSpan(600.dp, 120.dp), CellSpan(720.dp, 120.dp))

        assertThat(cellsToBuild(gap, from = 200.dp, viewport = 300.dp)).isEqualTo(0..1)
    }

    @Test
    fun `a row with no programmes builds none`() {
        assertThat(cellsToBuild(emptyList(), from = 0.dp, viewport = 400.dp)).isEqualTo(IntRange.EMPTY)
    }

    private companion object {
        const val MINUTE_MS = 60_000L
        const val DAY_MS = 24 * 60 * MINUTE_MS
    }
}
