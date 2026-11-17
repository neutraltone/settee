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
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.Stable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.key
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import app.opentv.data.repo.MediaKey
import app.opentv.data.repo.TitleCover
import app.opentv.ui.ContinueWatchingShelf
import app.opentv.ui.HomePick
import app.opentv.ui.HomeRow
import app.opentv.ui.LocalShellReturn
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
import app.opentv.ui.options.rememberRowHighlight
import app.opentv.ui.railReturnItem
import app.opentv.ui.restoreTarget
import kotlinx.coroutines.flow.filter

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
    val rowKeys = remember(resume.isNotEmpty(), rows) {
        listOfNotNull(CONTINUE_WATCHING.takeIf { resume.isNotEmpty() }) + rows.map { it.key }
    }
    LaunchedEffect(rowKeys.firstOrNull()) { if (atTheTop) column.scrollToItem(0) }

    // The highlighted cover's row goes to the top ([RowToTheTop]): a cover sits its row's title and
    // the room for its lift below the top of the row. Each row gets the usual sideways scrolling back.
    val sideways = LocalBringIntoViewSpec.current
    val titles = rememberTextMeasurer()
    val density = LocalDensity.current
    val coverTop = remember(density) {
        titles.measure("Ag", SetteeType.Section, maxLines = 1).size.height + with(density) { SHELF_LIFT_ROOM.toPx() }
    }
    val toTheTop = remember(coverTop) { RowToTheTop(coverTop) }

    // A row that goes while highlighted (its last Favourite removed) hands the highlight to the row now
    // standing where it stood, not the first.
    val rowHighlight = rememberRowHighlight(rowKeys, column) { it }

    // Back to For you from a title whose card has gone: the card now in its place.
    val back = rememberForYouReturn()
    val target = back.target { pickRows(resume, rows, cover) }
    val focus = remember(target) { target?.let { CardFocus(it.index) { back.restoring = null } } }
    LaunchedEffect(target?.row) { target?.let { column.scrollToItem(rowKeys.indexOf(it.row)) } }
    fun focusIn(row: String) = focus?.takeIf { target?.row == row }
    fun highlighted(row: String, item: String, index: Int) =
        back.highlighted(HomePick(row, pickRowKeys(resume, rows).indexOf(row), item, index))

    @Composable
    fun RowAtTop(index: Int, modifier: Modifier = Modifier, content: @Composable () -> Unit) =
        CompositionLocalProvider(LocalBringIntoViewSpec provides sideways) {
            Box(
                modifier
                    .then(rowHighlight.modifier(index, rowKeys[index], group = true))
                    .onFocusChanged { if (it.hasFocus) atTheTop = index == 0 },
            ) { content() }
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
                railReturnItem(key = CONTINUE_WATCHING, contentType = "continue-watching") {
                    RowAtTop(0) {
                        ContinueWatchingShelf(
                            resume,
                            large = false,
                            focus = focusIn(CONTINUE_WATCHING),
                            onResume = { card -> onResume(card.key) },
                            leave = { LeaveDirectly },
                            onHighlight = { card ->
                                highlighted(CONTINUE_WATCHING, card.titleKey, resume.indexOf(card))
                                // The film or show the card plays, so it shows in the backdrop as a cover's does.
                                card.titleId?.let(onHighlight)
                            },
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
                        val rowFocus = focusIn(row.key)
                        val rowState = rememberRowState(rowFocus)
                        val highlight = rememberRowHighlight(covers, rowState) { it.second.id }
                        Shelf(row.title, memory, state = rowState) {
                            itemsIndexed(covers, key = { _, pair -> pair.second.id }) { index, (title, art) ->
                                TitleArt(
                                    art,
                                    Modifier
                                        .takesFocus(index, rowFocus)
                                        .shelfCard(memory, "${art.id}")
                                        .then(highlight.modifier(index, art.id)),
                                    { id ->
                                        highlighted(row.key, "$id", index)
                                        onHighlight(id)
                                    },
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

/**
 * Where the Highlight goes back to on For you when Back to the main screen couldn't put it straight
 * back on its card (KeptShell asks, [LocalShellReturn]): the card last highlighted, or, when it has
 * left its row while the viewer was away, the card now in its place, as Home works it out
 * ([restoreTarget]). Without this nothing on For you answered, and the Highlight fell to its first card.
 */
@Stable
private class ForYouReturn(private val last: MutableState<HomePick?>) {
    /** The card being put back, until it has had the chance to take the Highlight. */
    var restoring by mutableStateOf<HomePick?>(null)

    fun target(rows: () -> List<HomeRow>): HomePick? = restoring?.let { restoreTarget(rows(), it) }

    fun highlighted(pick: HomePick) {
        last.value = pick
    }

    fun ask() {
        restoring = last.value
    }
}

/** For you's rows with cards in them, top to bottom, by key, as [restoreTarget] takes them. */
private fun <T> pickRows(resume: List<ResumeItem>, rows: List<TitleRow<T>>, cover: (T) -> TitleCover): List<HomeRow> {
    val cw = HomeRow(CONTINUE_WATCHING, resume.map { it.titleKey }).takeIf { resume.isNotEmpty() }
    return listOfNotNull(cw) + rows.filter { it.titles.isNotEmpty() }.map { row ->
        HomeRow(row.key, row.titles.map { "${cover(it).id}" })
    }
}

/** [pickRows]' keys, without working out every cover on each move of the Highlight. */
private fun pickRowKeys(resume: List<ResumeItem>, rows: List<TitleRow<*>>): List<String> =
    listOfNotNull(CONTINUE_WATCHING.takeIf { resume.isNotEmpty() }) +
        rows.filter { it.titles.isNotEmpty() }.map { it.key }

/** Kept through For you being built again under a title's page, after a player let it go. */
@Composable
private fun rememberForYouReturn(): ForYouReturn {
    val last = rememberSaveable(stateSaver = HomePick.Saver) { mutableStateOf<HomePick?>(null) }
    val back = remember(last) { ForYouReturn(last) }
    val shellReturn = LocalShellReturn.current
    // Collected rather than keyed on `asked`, which KeptShell lowers after a few frames (CoverGrid).
    LaunchedEffect(shellReturn) { snapshotFlow { shellReturn.asked }.filter { it }.collect { back.ask() } }
    return back
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

/** Continue Watching's key among For you's rows. */
private const val CONTINUE_WATCHING = "cw"

/** As much as a Shelf leaves above and below its covers: placeholders are as tall as a row. */
private val SHELF_LIFT_ROOM = 12.dp
