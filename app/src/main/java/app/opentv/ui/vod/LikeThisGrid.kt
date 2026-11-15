/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv.ui.vod

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.wrapContentHeight
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.testTag
import androidx.compose.ui.unit.dp
import app.opentv.R
import app.opentv.data.repo.TitleCover
import app.opentv.ui.design.ArtCardSize
import app.opentv.ui.design.SetteeColors
import app.opentv.ui.design.SetteeShapes
import app.opentv.ui.design.SetteeSpace
import app.opentv.ui.design.SetteeType
import app.opentv.ui.design.Text
import coil.compose.AsyncImage

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

/**
 * Under a show's last season, More like this's title and the tops of its first covers, faded out
 * downwards and out of the Highlight's reach, as the next season's first episode shows under any
 * other season's last: Down goes on there.
 */
@Composable
internal fun LikeThisPeek(covers: List<TitleCover>) {
    Column(
        Modifier
            .fillMaxWidth()
            .padding(top = SetteeSpace.RowGap)
            .clearAndSetSemantics { testTag = LIKE_THIS_PEEK }
            // Offscreen, so the fade masks the peek's own pixels rather than darkening what's behind.
            .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
            .drawWithContent {
                drawContent()
                drawRect(PeekFade, blendMode = BlendMode.DstIn)
            },
        verticalArrangement = Arrangement.spacedBy(SetteeSpace.RowGap),
    ) {
        Text(stringResource(R.string.vod_more_like_this), style = SetteeType.Section, maxLines = 1)
        Row(
            Modifier.fillMaxWidth().height(PEEK_COVER_TOPS).clipToBounds(),
            horizontalArrangement = Arrangement.spacedBy(SetteeSpace.CardGap),
        ) {
            covers.take(PEEK_COVERS).forEach { cover ->
                AsyncImage(
                    model = cover.posterUrl,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        // The whole cover, cut off by the row: its top, as the grid will show it.
                        .wrapContentHeight(Alignment.Top, unbounded = true)
                        .size(ArtCardSize.Portrait.width, ArtCardSize.Portrait.height)
                        .clip(SetteeShapes.Card)
                        .background(SetteeColors.Raised),
                )
            }
        }
    }
}

private fun LazyGridState.inFirstRow(coverId: Long?): Boolean =
    coverId != null && layoutInfo.visibleItemsInfo.firstOrNull { it.key == coverId }?.row == 0

/** Above and below the covers, for a highlighted one's lift and ring. */
private val LIFT_ROOM = 12.dp

/** Test tag on the faded More like this under a show's last season. */
internal const val LIKE_THIS_PEEK = "like_this_peek"

/** How much of each cover the peek shows: enough to read as covers, not so much it's a row. */
private val PEEK_COVER_TOPS = 72.dp

/** More covers than a screen is wide; the row cuts off the rest. */
private const val PEEK_COVERS = 8
