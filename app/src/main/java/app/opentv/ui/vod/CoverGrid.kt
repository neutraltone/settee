/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv.ui.vod

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.gestures.LocalBringIntoViewSpec
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items as gridItems
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.key
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import app.opentv.data.repo.TitleCover
import app.opentv.ui.LocalShellReturn
import app.opentv.ui.design.ArtCard
import app.opentv.ui.design.ArtCardSize
import app.opentv.ui.design.ScreenFrameScope
import app.opentv.ui.design.SetteeSpace
import app.opentv.ui.railReturnTarget
import coil.imageLoader
import coil.request.ImageRequest
import coil.size.Size
import kotlinx.coroutines.flow.filterNotNull

/**
 * A category's whole grid, with the same covers as the rows. Right out of the sidebar or the rail
 * comes back to it ([railReturnTarget]). [covers] is null while they load.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun ScreenFrameScope.CoverGrid(
    covers: List<TitleCover>?,
    loading: String,
    empty: String,
    onHighlight: (Long) -> Unit,
    onOpen: (Long) -> Unit,
    onOptions: ((Long) -> Unit)?,
) {
    when {
        covers == null -> return EmptyBrowse(VodEmpty.Loading(loading))
        covers.isEmpty() -> return EmptyBrowse(VodEmpty.Hint(empty, body = null))
    }
    val state = rememberLazyGridState()
    PrefetchCovers(state, covers)
    val back = rememberCoverReturn(state, covers)
    val room = with(LocalDensity.current) { GRID_LIFT_ROOM.toPx() }
    CompositionLocalProvider(LocalBringIntoViewSpec provides remember(room) { RowToTheTop(room) }) {
        CoverRows(state, covers, back, onHighlight, onOpen, onOptions)
    }
}

/**
 * Where the Highlight goes back to in the grid when Back to the main screen couldn't put it straight
 * back on its cover (KeptShell asks, [LocalShellReturn]): the title last highlighted, so it's found
 * after the grid was built again under a player, and the first cover when it has gone (#384).
 * Without this nothing in Movies or Shows answered, and the Highlight fell to the rail.
 *
 * By id, and failing that by title: a cover's id is the first of its Versions this device plays
 * ([TitleCover]), which can change while the title's page is open.
 */
@Stable
private class CoverReturn(private val lastId: MutableState<Long?>, private val lastTitle: MutableState<String?>) {
    /** The cover [requester] is on while the Highlight goes back. */
    var target: Long? by mutableStateOf(null)

    val requester = FocusRequester()

    fun highlighted(cover: TitleCover) {
        lastId.value = cover.id
        lastTitle.value = cover.title
    }

    /** Where in [covers] the Highlight goes back to. */
    fun indexIn(covers: List<TitleCover>): Int = covers.indexOfFirst { it.id == lastId.value }.takeIf { it >= 0 }
        ?: covers.indexOfFirst { it.title == lastTitle.value }.coerceAtLeast(0)
}

/** Saved, so it outlives the grid being built again under a player. */
@Composable
private fun rememberCoverReturn(state: LazyGridState, covers: List<TitleCover>): CoverReturn {
    val lastId = rememberSaveable { mutableStateOf<Long?>(null) }
    val lastTitle = rememberSaveable { mutableStateOf<String?>(null) }
    val back = remember(lastId, lastTitle) { CoverReturn(lastId, lastTitle) }
    val shown by rememberUpdatedState(covers)
    val shellReturn = LocalShellReturn.current
    LaunchedEffect(shellReturn.asked) {
        if (!shellReturn.asked) return@LaunchedEffect
        val index = back.indexIn(shown)
        back.target = shown[index].id
        if (state.layoutInfo.visibleItemsInfo.none { it.index == index }) state.scrollToItem(index)
        // The cover is composed with the requester on the frame after it's scrolled to.
        withFrameNanos {}
        runCatching { back.requester.requestFocus() }
    }
    return back
}

@Composable
private fun ScreenFrameScope.CoverRows(
    state: LazyGridState,
    covers: List<TitleCover>,
    back: CoverReturn,
    onHighlight: (Long) -> Unit,
    onOpen: (Long) -> Unit,
    onOptions: ((Long) -> Unit)?,
) {
    LazyVerticalGrid(
        state = state,
        // Columns as wide as a cover, so it's the size it is in a row. Adaptive ones share out the
        // width left over, which stretched each cover wider than For you's.
        columns = GridCells.FixedSize(ArtCardSize.Portrait.width),
        contentPadding = PaddingValues(vertical = GRID_LIFT_ROOM),
        horizontalArrangement = Arrangement.spacedBy(SetteeSpace.CardGap),
        verticalArrangement = Arrangement.spacedBy(SetteeSpace.RowGap),
        modifier = Modifier.fillMaxSize().railReturnTarget(),
    ) {
        gridItems(covers, key = { it.id }, contentType = { "cover" }) { cover ->
            TitleArt(
                cover,
                if (cover.id == back.target) Modifier.focusRequester(back.requester) else Modifier,
                { id ->
                    back.highlighted(cover)
                    onHighlight(id)
                },
                { onOpen(cover.id) },
                onOptions?.let { { it(cover.id) } },
            )
        }
    }
}

/**
 * Fetches covers [PREFETCH_ROWS] rows past the ones on screen, in the direction the viewer is
 * moving, so a normal pace of scrolling never shows a blank card (docs/LOOK.md, Shows and Movies).
 * The lazy grid's own prefetch composes only the next row, which a held Down outruns at once.
 *
 * Asked for at the size the card draws them, so the card finds them already decoded in Coil's
 * memory cache rather than decoding a full-size poster again.
 *
 * Those the viewer has gone past, and all of them once the grid closes or its covers change, are
 * cancelled. Posters mostly come from one host, and OkHttp fetches only five at a time from a host,
 * in the order asked: a held Down queues hundreds of covers for rows already passed, and the cards of
 * the row the viewer stops on wait behind every one of them, blank, which this prefetch is meant to
 * prevent. Finished ones stay in Coil's caches for a viewer who scrolls back.
 */
@Composable
private fun PrefetchCovers(state: LazyGridState, covers: List<TitleCover>) {
    val context = LocalContext.current
    val tile = with(LocalDensity.current) {
        Size(ArtCardSize.Portrait.width.roundToPx(), ArtCardSize.Portrait.height.roundToPx())
    }
    LaunchedEffect(state, covers, tile) {
        val loader = context.imageLoader
        val prefetcher = CoverPrefetcher(covers, PREFETCH_ROWS) { url, failed ->
            loader.enqueue(
                ImageRequest.Builder(context).data(url).size(tile).listener(onError = { _, _ -> failed() }).build(),
            )
        }
        try {
            snapshotFlow {
                state.layoutInfo.visibleItemsInfo.takeIf { it.isNotEmpty() }
                    ?.let { shown ->
                        ShownCovers(shown.first().index, shown.last().index, shown.maxOf { it.column } + 1)
                    }
            }.filterNotNull().collect { shown -> prefetcher.onShown(shown.first, shown.last, shown.columns) }
        } finally {
            prefetcher.cancelAll()
        }
    }
}

private data class ShownCovers(val first: Int, val last: Int, val columns: Int)

private const val PREFETCH_ROWS = 4

/** Above and below the grid's covers, for a highlighted one's lift and ring, as a Shelf leaves. */
private val GRID_LIFT_ROOM = 12.dp

/** A title's cover, the same in a row and a grid. */
@Composable
internal fun TitleArt(
    cover: TitleCover,
    modifier: Modifier,
    onHighlight: (Long) -> Unit,
    onOpen: () -> Unit,
    onOptions: (() -> Unit)?,
) {
    ArtCard(
        title = cover.title,
        artworkUrl = cover.posterUrl,
        onClick = onOpen,
        size = ArtCardSize.Portrait,
        modifier = modifier.onFocusChanged { if (it.isFocused) onHighlight(cover.id) },
        badge = badgeText(cover.badge),
        onOptions = onOptions,
    )
}
