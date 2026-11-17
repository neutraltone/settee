/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv.ui.vod

import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.Stable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull

/** A place on a title's page the viewer can leave it from, so Back can put the Highlight back there. */
internal sealed interface PageSpot {
    /** A name under one of a film's people headings: a director is often billed too, so the row counts. */
    data class Person(val row: PeopleRow, val name: String) : PageSpot

    /** A film's cover in its More like this. */
    data class Film(val id: Long) : PageSpot

    /** A show's cover in its More like this. */
    data class Show(val id: Long) : PageSpot

    data class Episode(val id: Long) : PageSpot

    /** A show's Cast button, whose panel closes when a name in it is picked. */
    data object Cast : PageSpot
}

/** The headings a film's people come under. By name, not string resource: resource ids change between builds. */
internal enum class PeopleRow { Cast, Director, Writer, Studio }

/**
 * Where the Highlight was on a title's page. Navigation disposes the page while the screen it opened covers
 * it, so this is kept with the page's saved state, and Back puts the Highlight back there (#429) rather
 * than on Play, as a person's page does (#384). Null while the Highlight is on the page's buttons, which
 * is where the page starts anyway.
 *
 * [start] is read once, as the page is built: every place the Highlight moves through writes the spot, and
 * reading it while composing would recompose every row and cover on the page with each press. It's let go
 * of ([settled]) once the page has placed its first highlight, so a grid shown again later starts at its top.
 */
@Stable
internal class ReturnSpot(private val saved: MutableState<PageSpot?>) {
    /** Where the viewer left the page from, as it was when the page was built; null when they didn't. */
    var start: PageSpot? = Snapshot.withoutReadObservation { saved.value }
        private set

    /** The page has placed its first highlight, back at [start] or not. */
    fun settled() {
        start = null
    }

    internal val requester = FocusRequester()

    internal fun highlighted(spot: PageSpot?) {
        saved.value = spot
    }

    /** Highlights [start] if it's on the page; false when the page should start as new. */
    fun restore(): Boolean =
        start != null && runCatching { requester.requestFocus(FocusDirection.Enter) }.getOrDefault(false)

    /**
     * [restore], frame by frame, for a spot known to be on the page but in a lazy row or grid that is
     * still being laid out or scrolled to it.
     */
    suspend fun restoreSoon(): Boolean = withTimeoutOrNull(LAYOUT_WAIT_MILLIS) {
        while (!restore()) withFrameNanos {}
        true
    } == true

    /**
     * Back to [start] when it's one of the covers [items] returns. More like this is read after the page
     * shows, so the covers are waited for; null is still reading. [show] makes the cover at that index
     * reachable first: the row scrolled, or the grid shown. False when the covers took too long or [start]
     * isn't among them.
     */
    suspend fun <T> restoreAmong(
        items: () -> List<T>?,
        spotOf: (T) -> PageSpot,
        show: suspend (index: Int) -> Unit,
    ): Boolean {
        val read = withTimeoutOrNull(LIKE_THIS_WAIT_MILLIS) { snapshotFlow(items).first { it != null } }
        val index = read?.indexOfFirst { spotOf(it) == start } ?: -1
        if (index < 0) return false
        show(index)
        return restoreSoon()
    }
}

/** A [ReturnSpot] for [page], kept with the page's saved state. */
@Composable
internal fun rememberReturnSpot(page: Any): ReturnSpot {
    val saved = rememberSaveable(page, saver = PageSpotSaver) { mutableStateOf<PageSpot?>(null) }
    return remember(saved) { ReturnSpot(saved) }
}

/**
 * Marks a place the viewer can leave the page from as [spot], ahead of what makes it focusable. Null for
 * the page's buttons: highlighting one forgets the spot, so Back from playing lands on Play as before.
 */
internal fun Modifier.returnSpot(returnSpot: ReturnSpot, spot: PageSpot?): Modifier =
    (if (spot != null && spot == returnSpot.start) focusRequester(returnSpot.requester) else this)
        .onFocusChanged { if (it.isFocused) returnSpot.highlighted(spot) }

/**
 * Scrolls a lazy row to item [index], once the row is laid out, unless it shows already: an item
 * scrolled out of the row isn't there to take the Highlight. Usually the row's own saved scroll has it
 * in view; this is for when what the row holds changed while the page was away. Nothing for -1.
 */
internal suspend fun LazyListState.bringOut(index: Int) = bringOut(
    index,
    laidOut = { layoutInfo.totalItemsCount > 0 },
    showing = { layoutInfo.visibleItemsInfo.any { it.index == index } },
    scroll = { scrollToItem(index) },
)

/** [LazyListState.bringOut] for a grid. */
internal suspend fun LazyGridState.bringOut(index: Int) = bringOut(
    index,
    laidOut = { layoutInfo.totalItemsCount > 0 },
    showing = { layoutInfo.visibleItemsInfo.any { it.index == index } },
    scroll = { scrollToItem(index) },
)

private suspend fun bringOut(index: Int, laidOut: () -> Boolean, showing: () -> Boolean, scroll: suspend () -> Unit) {
    if (index < 0) return
    snapshotFlow(laidOut).first { it }
    if (!showing()) scroll()
}

/** Saved as text, which a Bundle holds: the kind, then what picks it out. */
private val PageSpotSaver = Saver<MutableState<PageSpot?>, String>(
    save = { state -> state.value?.let(::encode).orEmpty() },
    restore = { text -> mutableStateOf(decode(text)) },
)

private fun encode(spot: PageSpot): String = when (spot) {
    is PageSpot.Person -> "person:${spot.row.name}:${spot.name}"
    is PageSpot.Film -> "film:${spot.id}"
    is PageSpot.Show -> "show:${spot.id}"
    is PageSpot.Episode -> "episode:${spot.id}"
    PageSpot.Cast -> "cast"
}

private fun decode(text: String): PageSpot? {
    val parts = text.split(':', limit = 3)
    val id = parts.getOrNull(1)?.toLongOrNull()
    return when (parts.first()) {
        "person" -> PeopleRow.entries.firstOrNull { it.name == parts.getOrNull(1) }
            ?.let { row -> parts.getOrNull(2)?.let { PageSpot.Person(row, it) } }
        "film" -> id?.let(PageSpot::Film)
        "show" -> id?.let(PageSpot::Show)
        "episode" -> id?.let(PageSpot::Episode)
        "cast" -> PageSpot.Cast
        else -> null
    }
}

/** For a lazy row or grid to be laid out and scrolled to the spot. */
private const val LAYOUT_WAIT_MILLIS = 500L

/** Long enough for More like this to be read on a Stick; Back lands on the main button if it takes longer. */
private const val LIKE_THIS_WAIT_MILLIS = 2_000L
