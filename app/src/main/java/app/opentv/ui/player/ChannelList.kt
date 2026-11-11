/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv.ui.player

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.opentv.R
import app.opentv.player.PlaybackQueue
import app.opentv.ui.design.HighlightStyle
import app.opentv.ui.design.HighlightSurface
import app.opentv.ui.design.SetteeColors
import app.opentv.ui.design.SetteeShapes
import app.opentv.ui.design.SetteeSpace
import app.opentv.ui.design.SetteeType
import app.opentv.ui.design.Text
import app.opentv.ui.options.rememberRowHighlight
import coil.compose.AsyncImage

/**
 * The live player's channel list (docs/LOOK.md, Live TV): a solid panel down the left, with the
 * channel the viewer was last on pinned at the top. Moving through it never changes channel; OK on
 * a row does, through [onPick]. What's on each channel is asked for once, as the list opens, by
 * [onNow]: one read for the whole list, keyed by [PlaybackQueue.Item.id], rather than one per row.
 */
@Composable
internal fun ChannelList(
    queue: List<PlaybackQueue.Item>,
    currentId: Long?,
    previousId: Long?,
    onNow: suspend (Long) -> Map<Long, String>,
    firstFocus: FocusRequester,
    onPick: (PlaybackQueue.Item) -> Unit,
    onOptions: (PlaybackQueue.Item) -> Unit,
    modifier: Modifier = Modifier,
) {
    // Keyed by nothing that changes while it's open: the list is composed afresh each time it opens.
    val whatsOn = rememberGuideFor(LIST_KEY, onNow).orEmpty()
    val currentIndex = queue.indexOfFirst { currentId in it.qualityIds }.coerceAtLeast(0)
    val listState = rememberLazyListState()
    val highlight = rememberRowHighlight(queue, listState) { it.id }
    LaunchedEffect(Unit) { runCatching { listState.scrollToItem(currentIndex) } }
    // Solid rather than see-through over the picture: see docs/LOOK.md, Motion and cost.
    Column(
        modifier
            .fillMaxHeight()
            .width(PANEL_WIDTH)
            .background(SetteeColors.Background)
            .padding(vertical = SetteeSpace.ScreenTopBottom, horizontal = 16.dp),
    ) {
        Text(
            stringResource(R.string.common_channels),
            SetteeType.Section,
            color = SetteeColors.QuietText,
            modifier = Modifier.padding(horizontal = 12.dp),
        )
        Spacer(Modifier.height(12.dp))
        val lastItem = previousId?.let { pid -> queue.firstOrNull { pid in it.qualityIds } }
        if (lastItem != null && currentId !in lastItem.qualityIds) {
            ChannelListRow(
                item = lastItem,
                onNow = whatsOn[lastItem.id],
                playing = false,
                leadingLabel = stringResource(R.string.player_last),
                onClick = { onPick(lastItem) },
                onOptions = { onOptions(lastItem) },
            )
            Spacer(Modifier.height(8.dp))
        }
        LazyColumn(state = listState) {
            itemsIndexed(queue, key = { _, item -> item.id }) { index, item ->
                ChannelListRow(
                    item = item,
                    onNow = whatsOn[item.id],
                    playing = currentId in item.qualityIds,
                    onClick = { onPick(item) },
                    onOptions = { onOptions(item) },
                    modifier = highlight.modifier(index, item.id)
                        .then(if (index == currentIndex) Modifier.focusRequester(firstFocus) else Modifier),
                )
            }
        }
    }
}

@Composable
private fun ChannelListRow(
    item: PlaybackQueue.Item,
    onNow: String?,
    playing: Boolean,
    onClick: () -> Unit,
    onOptions: () -> Unit,
    modifier: Modifier = Modifier,
    leadingLabel: String? = null,
) {
    HighlightSurface(
        onClick = onClick,
        onOptions = onOptions,
        // The channel that's on rests on a Raised fill, so the list still says which it is.
        style = if (playing) HighlightStyle.CurrentRow else HighlightStyle.Row,
        modifier = modifier.fillMaxWidth(),
    ) { highlighted ->
        val text = if (highlighted) SetteeColors.OnAccent else SetteeColors.Text
        val quiet = if (highlighted) SetteeColors.OnAccent else SetteeColors.QuietText
        Row(
            Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // A "Last" tag, or the channel's number if the provider gives one, in a fixed-width slot
            // so the logos and names line up down the list.
            Text(
                leadingLabel ?: item.number?.toString().orEmpty(),
                SetteeType.Body,
                color = quiet,
                maxLines = 1,
                modifier = Modifier.width(44.dp),
            )
            AsyncImage(
                model = item.logoUrl,
                contentDescription = null,
                modifier = Modifier.size(LOGO_SIZE).clip(SetteeShapes.Thumbnail),
            )
            Column(Modifier.weight(1f)) {
                Text(item.name, SetteeType.RowTitle, color = text, maxLines = 1)
                if (onNow != null) Text(onNow, SetteeType.Body, color = quiet, maxLines = 1)
            }
        }
    }
}

private const val LIST_KEY = "channel-list"
private val PANEL_WIDTH = 400.dp
private val LOGO_SIZE = 40.dp
