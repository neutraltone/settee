/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv

import app.opentv.ui.PickRow
import app.opentv.ui.RowPick
import app.opentv.ui.restoreTarget
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class HomeRestoreTargetTest {

    private val cw = PickRow("cw", listOf("movie:9"))

    @Test
    fun `a picked card still in its row is where the highlight goes, wherever it has moved to`() {
        val rows = listOf(PickRow("movies", listOf("5", "2", "4")))

        assertThat(restoreTarget(rows, RowPick("movies", 1, "4", 1))).isEqualTo(RowPick("movies", 0, "4", 2))
    }

    @Test
    fun `a picked card that has gone hands the highlight to the card now in its place`() {
        val rows = listOf(cw, PickRow("movies", listOf("2", "6", "7")))

        assertThat(restoreTarget(rows, RowPick("movies", 1, "4", 1))).isEqualTo(RowPick("movies", 1, "6", 1))
    }

    @Test
    fun `a picked last card that has gone hands the highlight to the new last card`() {
        val rows = listOf(cw, PickRow("movies", listOf("2", "4")))

        assertThat(restoreTarget(rows, RowPick("movies", 1, "7", 2))).isEqualTo(RowPick("movies", 1, "4", 1))
    }

    @Test
    fun `when the picked card's row has gone, the highlight goes to the first card of the row in its place`() {
        val rows = listOf(cw, PickRow("channels", listOf("3", "8")))

        assertThat(restoreTarget(rows, RowPick("movies", 1, "4", 0))).isEqualTo(RowPick("channels", 1, "3", 0))
    }

    @Test
    fun `when the picked card's row was the last and has gone, the highlight goes to the new last row`() {
        val rows = listOf(cw)

        assertThat(restoreTarget(rows, RowPick("movies", 1, "4", 0))).isEqualTo(RowPick("cw", 0, "movie:9", 0))
    }

    @Test
    fun `with no rows left there is nowhere to go back to`() {
        assertThat(restoreTarget(emptyList(), RowPick("movies", 0, "4", 0))).isNull()
    }
}
