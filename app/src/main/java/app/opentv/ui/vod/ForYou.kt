/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv.ui.vod

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.gestures.LocalBringIntoViewSpec
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.key
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import app.opentv.data.repo.MediaKey
import app.opentv.data.repo.TitleCover
import app.opentv.ui.ContinueWatchingShelf
import app.opentv.ui.ResumeItem
import app.opentv.ui.design.ArtCardSize
import app.opentv.ui.design.ScreenFrameScope
import app.opentv.ui.design.SetteeColors
import app.opentv.ui.design.SetteeShapes
import app.opentv.ui.design.SetteeSpace
import app.opentv.ui.design.SetteeType
import app.opentv.ui.design.Shelf
import app.opentv.ui.design.Text
import app.opentv.ui.design.rememberShelfMemory
import app.opentv.ui.design.shelfCard
import app.opentv.ui.options.LeaveDirectly
import app.opentv.ui.railReturnItem

/** For you's rows, top to bottom: Continue Watching, then [rows]. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun <T> ScreenFrameScope.ForYou(
    resume: List<ResumeItem>,
    rows: List<TitleRow<T>>,
    onResume: (MediaKey) -> Unit,
    cover: (T) -> TitleCover,
    onOpen: (T) -> Unit,
    onHighlight: (Long) -> Unit,
    options: @Composable (T) -> (() -> Unit)?,
) {
    val column = rememberLazyListState()
    // Until the viewer moves down, a row that lands above the first (Recommended, worked out after
    // the others) is shown at the top, rather than the list holding on to the row that was first.
    var atTheTop by remember { mutableStateOf(true) }
    val firstKey = if (resume.isNotEmpty()) "cw" else rows.firstOrNull()?.key
    LaunchedEffect(firstKey) { if (atTheTop) column.scrollToItem(0) }

    // The highlighted cover's row goes to the top ([RowToTheTop]): a cover sits its row's title and
    // the room for its lift below the top of the row. Each row gets the usual sideways scrolling back.
    val sideways = LocalBringIntoViewSpec.current
    val titles = rememberTextMeasurer()
    val density = LocalDensity.current
    val coverTop = remember(density) {
        titles.measure("Ag", SetteeType.Section, maxLines = 1).size.height + with(density) { SHELF_LIFT_ROOM.toPx() }
    }
    val toTheTop = remember(coverTop) { RowToTheTop(coverTop) }

    @Composable
    fun RowAtTop(index: Int, modifier: Modifier = Modifier, content: @Composable () -> Unit) =
        CompositionLocalProvider(LocalBringIntoViewSpec provides sideways) {
            Box(modifier.onFocusChanged { if (it.hasFocus) atTheTop = index == 0 }) { content() }
        }

    // Room under the last row for it to come to the top too, rather than the list stopping at its
    // end with the row above part-shown. Always there, sized for a row of covers: worked out from
    // the last row once it was on screen, it came too late for the press that brought it on, which
    // stopped short on a Stick.
    val rowHeight = coverTop + with(density) { (ArtCardSize.Portrait.height + SHELF_LIFT_ROOM).toPx() }
    val roomUnderLast by remember(column, rowHeight) {
        derivedStateOf { (column.layoutInfo.viewportSize.height - rowHeight).coerceAtLeast(0f) }
    }
    CompositionLocalProvider(LocalBringIntoViewSpec provides toTheTop) {
        LazyColumn(
            state = column,
            contentPadding = PaddingValues(bottom = with(density) { roomUnderLast.toDp() }),
            verticalArrangement = Arrangement.spacedBy(SetteeSpace.RowGap),
            modifier = Modifier.fillMaxSize().focusGroup(),
        ) {
            val first = if (resume.isNotEmpty()) 1 else 0
            if (resume.isNotEmpty()) {
                railReturnItem(key = "cw", contentType = "continue-watching") {
                    RowAtTop(0) {
                        ContinueWatchingShelf(
                            resume,
                            large = false,
                            focus = null,
                            onResume = onResume,
                            leave = { LeaveDirectly },
                            // The film or show the card plays, so it shows in the backdrop as a cover's does.
                            onHighlight = { card -> card.titleId?.let(onHighlight) },
                        )
                    }
                }
            }
            rows.forEachIndexed { i, row ->
                railReturnItem(key = row.key, contentType = "covers") {
                    RowAtTop(first + i, row.modifier) {
                        if (row.titles.isEmpty()) {
                            PlaceholderShelf(row.title)
                            return@RowAtTop
                        }
                        val covers = remember(row.titles) { row.titles.map { it to cover(it) } }
                        val memory = rememberShelfMemory(remember(covers) { covers.map { "${it.second.id}" } })
                        Shelf(row.title, memory) {
                            items(covers, key = { it.second.id }) { (title, art) ->
                                TitleArt(
                                    art,
                                    Modifier.shelfCard(memory, "${art.id}"),
                                    onHighlight,
                                    { onOpen(title) },
                                    options(title),
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

/** A row still being worked out: its title over blank covers, which can't take the Highlight. */
@Composable
internal fun ScreenFrameScope.PlaceholderShelf(title: String) {
    Column {
        Text(title, SetteeType.Section, maxLines = 1)
        Row(
            horizontalArrangement = Arrangement.spacedBy(SetteeSpace.CardGap),
            modifier = Modifier.bleed().padding(bleedPadding).padding(vertical = SHELF_LIFT_ROOM),
        ) {
            repeat(PLACEHOLDER_COVERS) {
                Box(
                    Modifier
                        .size(ArtCardSize.Portrait.width, ArtCardSize.Portrait.height)
                        .background(SetteeColors.Surface, SetteeShapes.Card),
                )
            }
        }
    }
}

private const val PLACEHOLDER_COVERS = 8

/** As much as a Shelf leaves above and below its covers: placeholders are as tall as a row. */
private val SHELF_LIFT_ROOM = 12.dp
