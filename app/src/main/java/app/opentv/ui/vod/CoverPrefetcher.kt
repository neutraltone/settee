/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv.ui.vod

import app.opentv.data.repo.TitleCover
import coil.request.Disposable

/**
 * Decides which of a grid's [covers] to fetch ahead of the ones on screen, [rowsAhead] rows in the
 * direction the viewer is moving, and gives up on those the viewer has gone past. [fetch] asks for a
 * poster and returns its request.
 */
internal class CoverPrefetcher(
    private val covers: List<TitleCover>,
    private val rowsAhead: Int,
    private val fetch: (url: String) -> Disposable,
) {
    private val asked = HashSet<Long>()
    private val outstanding = HashMap<Long, Disposable>()
    private var previousFirst = -1
    private var upwards = false

    /** The grid now shows covers [first] to [last], [columns] to a row. */
    fun onShown(first: Int, last: Int, columns: Int) {
        val ahead = rowsAhead * columns
        // The grid reports every frame of a scroll, most with the same covers on screen.
        if (first != previousFirst) upwards = first < previousFirst
        previousFirst = first
        // Clamped both ways: just after the covers change, the layout can still be the old one's.
        val window = if (upwards) {
            (first - ahead).coerceAtLeast(0)..last.coerceAtMost(covers.lastIndex)
        } else {
            first.coerceAtMost(covers.size)..(last + ahead).coerceAtMost(covers.lastIndex)
        }
        cancelOutside(window)
        val toFetch = if (upwards) window.first until first.coerceAtMost(covers.size) else last + 1..window.last
        for (index in toFetch) {
            val cover = covers[index]
            val url = cover.posterUrl ?: continue
            if (asked.add(cover.id)) outstanding[cover.id] = fetch(url)
        }
    }

    /** Gives up on every prefetch that hasn't finished, as the grid goes or its covers change. */
    fun cancelAll() = cancelOutside(IntRange.EMPTY)

    private fun cancelOutside(window: IntRange) {
        val inWindow = window.mapTo(HashSet()) { covers[it].id }
        val iterator = outstanding.entries.iterator()
        while (iterator.hasNext()) {
            val (id, request) = iterator.next()
            when {
                request.isDisposed -> iterator.remove()
                id !in inWindow -> {
                    request.dispose()
                    asked -= id
                    iterator.remove()
                }
            }
        }
    }
}
