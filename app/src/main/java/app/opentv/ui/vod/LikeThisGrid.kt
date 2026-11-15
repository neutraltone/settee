/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv.ui.vod

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.unit.dp
import app.opentv.data.repo.TitleCover
import app.opentv.ui.design.ArtCardSize
import app.opentv.ui.design.SetteeSpace

/**
 * A show's More like this, where its episodes were: a grid of the same covers as a category's. The first
 * cover takes [firstCover], for Down from the More like this pill; Up from the first row calls
 * [onUpFromFirstRow], back to that pill; OK on a cover calls [onOpenSeries].
 */
@Composable
internal fun LikeThisGrid(
    covers: List<TitleCover>,
    firstCover: FocusRequester,
    onFocusChanged: (hasFocus: Boolean) -> Unit,
    onUpFromFirstRow: () -> Unit,
    onOpenSeries: (Long) -> Unit,
) {
    val grid = rememberLazyGridState()
    // By id, not place: the covers can be read again while one is highlighted.
    var highlighted by remember { mutableStateOf<Long?>(null) }
    LazyVerticalGrid(
        state = grid,
        columns = GridCells.FixedSize(ArtCardSize.Portrait.width),
        contentPadding = PaddingValues(vertical = LIFT_ROOM),
        horizontalArrangement = Arrangement.spacedBy(SetteeSpace.CardGap),
        verticalArrangement = Arrangement.spacedBy(SetteeSpace.RowGap),
        modifier = Modifier
            .fillMaxWidth()
            .onFocusChanged { onFocusChanged(it.hasFocus) }
            .onPreviewKeyEvent { e ->
                val up = e.type == KeyEventType.KeyDown && e.key == Key.DirectionUp
                (up && grid.inFirstRow(highlighted)).also { if (it) onUpFromFirstRow() }
            },
    ) {
        itemsIndexed(covers, key = { _, cover -> cover.id }) { index, cover ->
            TitleArt(
                cover = cover,
                modifier = if (index == 0) Modifier.focusRequester(firstCover) else Modifier,
                onHighlight = { highlighted = cover.id },
                onOpen = { onOpenSeries(cover.id) },
                onOptions = null,
            )
        }
    }
}

private fun LazyGridState.inFirstRow(coverId: Long?): Boolean =
    coverId != null && layoutInfo.visibleItemsInfo.firstOrNull { it.key == coverId }?.row == 0

/** Above and below the covers, for a highlighted one's lift and ring. */
private val LIFT_ROOM = 12.dp
