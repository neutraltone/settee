/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv.ui

import androidx.compose.runtime.saveable.listSaver

/**
 * One of a screen's rows of cards as the Highlight sees it (Home's, For you's): its list key and its
 * cards' keys, in order. Only rows with cards in them: a row still being worked out has none to take it.
 */
internal class PickRow(val key: String, val items: List<String>)

/**
 * A card the viewer picked on Home or For you: its row and the card's key, with where each stood, so the
 * Highlight has somewhere to go if the card itself has gone by the time they come back. [rowIndex] is
 * among the [PickRow]s, the rows with cards in them, which isn't always where the row is in the list.
 */
internal data class RowPick(val row: String, val rowIndex: Int, val item: String, val index: Int) {
    companion object {
        val Saver = listSaver<RowPick?, Any>(
            save = { pick -> pick?.run { listOf(row, rowIndex, item, index) }.orEmpty() },
            restore = {
                it.takeIf {
                    it.size == 4
                }?.let { RowPick(it[0] as String, it[1] as Int, it[2] as String, it[3] as Int) }
            },
        )
    }
}

/** Where the Highlight goes on coming back to a screen after [pick], given the [rows] it has now. */
internal fun restoreTarget(rows: List<PickRow>, pick: RowPick): RowPick? {
    val rowIndex = rows.indexOfFirst { it.key == pick.row }
    return when {
        rows.isEmpty() -> null
        // The whole row has gone (its last Favourite removed, say): the row that now stands where it
        // stood takes the Highlight, as it does when a row goes while the viewer is on the screen.
        rowIndex < 0 -> pick.rowIndex.coerceAtMost(rows.lastIndex).let {
            RowPick(rows[it].key, it, rows[it].items.first(), 0)
        }
        else -> {
            val items = rows[rowIndex].items
            val index = items.indexOf(pick.item).takeIf { it >= 0 } ?: pick.index.coerceAtMost(items.lastIndex)
            RowPick(pick.row, rowIndex, items[index], index)
        }
    }
}
