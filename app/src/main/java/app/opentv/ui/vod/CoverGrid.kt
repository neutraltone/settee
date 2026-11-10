/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv.ui.vod

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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.key
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.opentv.R
import app.opentv.data.repo.TitleCover
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
 * comes back to it ([railReturnTarget]).
 */
@Composable
internal fun ScreenFrameScope.CoverGrid(
    covers: List<TitleCover>,
    loading: String,
    onHighlight: (Long) -> Unit,
    onOpen: (Long) -> Unit,
    onOptions: ((Long) -> Unit)?,
) {
    if (covers.isEmpty()) {
        EmptyBrowse(VodEmpty.Loading(loading))
        return
    }
    val state = rememberLazyGridState()
    PrefetchCovers(state, covers)
    LazyVerticalGrid(
        state = state,
        columns = GridCells.Adaptive(ArtCardSize.Portrait.width),
        contentPadding = PaddingValues(vertical = GRID_LIFT_ROOM),
        horizontalArrangement = Arrangement.spacedBy(SetteeSpace.CardGap),
        verticalArrangement = Arrangement.spacedBy(SetteeSpace.RowGap),
        modifier = Modifier.fillMaxSize().railReturnTarget(),
    ) {
        gridItems(covers, key = { it.id }, contentType = { "cover" }) { cover ->
            TitleArt(cover, Modifier, onHighlight, { onOpen(cover.id) }, onOptions?.let { { it(cover.id) } })
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
 */
@Composable
private fun PrefetchCovers(state: LazyGridState, covers: List<TitleCover>) {
    val context = LocalContext.current
    val tile = with(LocalDensity.current) {
        Size(ArtCardSize.Portrait.width.roundToPx(), ArtCardSize.Portrait.height.roundToPx())
    }
    LaunchedEffect(state, covers, tile) {
        val loader = context.imageLoader
        val asked = HashSet<Long>()
        var previousFirst = state.firstVisibleItemIndex
        snapshotFlow {
            state.layoutInfo.visibleItemsInfo.takeIf { it.isNotEmpty() }
                ?.let { shown -> ShownCovers(shown.first().index, shown.last().index, shown.maxOf { it.column } + 1) }
        }.filterNotNull().collect { shown ->
            val ahead = PREFETCH_ROWS * shown.columns
            val upwards = shown.first < previousFirst
            previousFirst = shown.first
            val range = if (upwards) {
                (shown.first - ahead).coerceAtLeast(0) until shown.first
            } else {
                shown.last + 1..(shown.last + ahead).coerceAtMost(covers.lastIndex)
            }
            for (index in range) {
                val cover = covers[index]
                val url = cover.posterUrl ?: continue
                if (asked.add(cover.id)) loader.enqueue(ImageRequest.Builder(context).data(url).size(tile).build())
            }
        }
    }
}

private class ShownCovers(val first: Int, val last: Int, val columns: Int)

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
        badge = rememberQualityBadge(cover.name)
            ?: if (cover.qualities > 1) stringResource(R.string.guide_qualities_count, cover.qualities) else null,
        onOptions = onOptions,
    )
}
