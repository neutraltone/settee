/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv.ui.vod

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.gestures.LocalBringIntoViewSpec
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.key
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.rememberTextMeasurer
import app.opentv.R
import app.opentv.data.repo.MediaKey
import app.opentv.data.repo.TitleCover
import app.opentv.ui.ContinueWatchingShelf
import app.opentv.ui.OnShellReturn
import app.opentv.ui.PickRow
import app.opentv.ui.WhenDrawn
import app.opentv.ui.ResumeItem
import app.opentv.ui.RowPick
import app.opentv.ui.design.ArtCardSize
import app.opentv.ui.design.ScreenFrameScope
import app.opentv.ui.design.SetteeSpace
import app.opentv.ui.design.SetteeType
import app.opentv.ui.design.Shelf
import app.opentv.ui.design.SkeletonCards
import app.opentv.ui.design.SkeletonShelf
import app.opentv.ui.design.rememberShelfMemory
import app.opentv.ui.design.shelfCard
import app.opentv.ui.options.LeaveDirectly
import app.opentv.ui.options.rememberRowHighlight
import app.opentv.ui.railReturnItem
import app.opentv.ui.rememberOpeningPlace
import app.opentv.ui.restoreTarget

/**
 * For you's rows, top to bottom: Continue Watching, then [rows]. While [resumeLoading], Continue
 * Watching's place is held by a skeleton, as a pending row's is.
 */
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
    resumeLoading: Boolean = false,
    /** Once a row has drawn with its cards: the end of opening Movies or Shows. */
    onFirstRowDrawn: () -> Unit = {},
) {
    val column = rememberLazyListState()
    // Until the viewer moves down, a row that lands above the first (Recommended, worked out after
    // the others) is shown at the top, rather than the list holding on to the row that was first.
    var atTheTop by remember { mutableStateOf(true) }
    val rowKeys = rowKeys(resume, rows, resumeLoading)
    WhenDrawn(rowKeys.withCards.isNotEmpty(), onFirstRowDrawn)
    LaunchedEffect(rowKeys.all.firstOrNull()) { if (atTheTop) column.scrollToItem(0) }

    // The highlighted cover's row goes to the top ([RowToTheTop]): a cover sits its row's title and
    // the room for its lift below the top of the row. Each row gets the usual sideways scrolling back.
    val sideways = LocalBringIntoViewSpec.current
    val titles = rememberTextMeasurer()
    val density = LocalDensity.current
    val coverTop = remember(density) {
        titles.measure("Ag", SetteeType.Section, maxLines = 1).size.height +
            with(density) { SetteeSpace.LiftRoom.toPx() }
    }
    val toTheTop = remember(coverTop) { RowToTheTop(coverTop) }

    // A row that goes while highlighted (its last Favourite removed) hands the Highlight to the row now
    // standing where it stood, not the first.
    val rowHighlight = rememberRowHighlight(rowKeys.all, column) { it }

    // Back to For you from a title whose card has gone: the card now in its place.
    val back = rememberForYouReturn()
    val target = back.rememberTarget(column, rowKeys) { pickRows(rowKeys, resume, rows, cover) }
    val focus = remember(target) { target?.let { CardFocus(it.index) { back.restoring = null } } }
    // The top row's place holds the Highlight For you opens on until the row lands (#581).
    val topRow = rowKeys.all.firstOrNull()
    val opening = rememberOpeningPlace(topPlace = topRow.takeIf { rowKeys.topIsPlace })
    fun focusIn(row: String) = opening.focusFor(row == topRow, otherwise = focus?.takeIf { target?.row == row })
    fun openingIn(row: String) = opening.modifierIf(row == topRow)
    fun highlighted(row: String, item: String, index: Int) =
        back.highlighted(RowPick(row, rowKeys.withCards.indexOf(row), item, index))

    @Composable
    fun RowAtTop(index: Int, modifier: Modifier = Modifier, content: @Composable () -> Unit) =
        CompositionLocalProvider(LocalBringIntoViewSpec provides sideways) {
            Box(
                modifier
                    .then(rowHighlight.groupModifier(index, rowKeys.all[index]))
                    .onFocusChanged { if (it.hasFocus) atTheTop = index == 0 },
            ) { content() }
        }

    // Room under the last row for it to come to the top too, rather than the list stopping at its
    // end with the row above part-shown. Always there, sized for a row of covers: worked out from
    // the last row once it was on screen, it came too late for the press that brought it on, which
    // stopped short on a Stick.
    val rowHeight = coverTop + with(density) { (ArtCardSize.Portrait.height + SetteeSpace.LiftRoom).toPx() }
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
            // Continue Watching's place, with its cards or a skeleton, comes before the other rows.
            val first = rowKeys.all.size - rows.size
            if (first == 1) {
                railReturnItem(key = CONTINUE_WATCHING, contentType = "continue-watching") {
                    RowAtTop(0) {
                        if (resume.isEmpty()) {
                            SkeletonShelf(
                                stringResource(R.string.vod_continue_watching),
                                SkeletonCards.ContinueWatching(largeFirst = false),
                                opening = openingIn(CONTINUE_WATCHING),
                            )
                            return@RowAtTop
                        }
                        ContinueWatchingShelf(
                            resume,
                            large = false,
                            focus = focusIn(CONTINUE_WATCHING),
                            onResume = { card -> onResume(card.key) },
                            leave = { LeaveDirectly },
                            onHighlight = { index, card ->
                                highlighted(CONTINUE_WATCHING, card.titleKey, index)
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
                            SkeletonShelf(row.title, opening = openingIn(row.key))
                            return@RowAtTop
                        }
                        val covers = remember(row.titles) { row.titles.map { it to cover(it) } }
                        val memory = rememberShelfMemory(remember(covers) { covers.map { it.second.cardKey } })
                        val rowFocus = focusIn(row.key)
                        val rowState = rememberRowState(rowFocus)
                        val highlight = rememberRowHighlight(covers, rowState) { it.second.cardKey }
                        Shelf(row.title, memory, state = rowState) {
                            itemsIndexed(covers, key = { _, pair -> pair.second.cardKey }) { index, (title, art) ->
                                TitleArt(
                                    art,
                                    Modifier
                                        .takesFocus(index, rowFocus)
                                        .shelfCard(memory, art.cardKey)
                                        .then(highlight.modifier(index, art.cardKey)),
                                    { id ->
                                        highlighted(row.key, art.cardKey, index)
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
 * back on its card (KeptShell asks, [OnShellReturn]): the card last highlighted, or, when it has left
 * its row while the viewer was away, the card now in its place, as Home works it out ([restoreTarget]).
 * Without this nothing on For you answered, and the Highlight fell to its first card.
 */
@Stable
private class ForYouReturn(private val last: MutableState<RowPick?>) {
    /** The card being put back, until it has had the chance to take the Highlight. */
    var restoring by mutableStateOf<RowPick?>(null)

    fun highlighted(pick: RowPick) {
        last.value = pick
    }

    /** Spent, as Home's pick is: For you come back to any other way starts afresh. */
    fun ask() {
        restoring = last.value
        last.value = null
    }
}

/**
 * Where [ForYouReturn.restoring] lands in [rows], the rows with cards in them, following them as they
 * change until the card has taken the Highlight; [column] scrolls to its row.
 */
@Composable
private fun ForYouReturn.rememberTarget(column: LazyListState, keys: RowKeys, rows: () -> List<PickRow>): RowPick? {
    val pick = restoring
    // Worked out only while putting the Highlight back, so not on every recomposition.
    val target = pick?.let { restoreTarget(rows(), it) }
    LaunchedEffect(target) {
        // Nowhere left to go (every row gone): nothing to follow the rows for any more.
        if (target == null) restoring = null else column.scrollToItem(keys.all.indexOf(target.row))
    }
    return target
}

/** Kept through For you being built again under a title's page, after a player let it go. */
@Composable
private fun rememberForYouReturn(): ForYouReturn {
    val last = rememberSaveable(stateSaver = RowPick.Saver) { mutableStateOf<RowPick?>(null) }
    val back = remember(last) { ForYouReturn(last) }
    OnShellReturn { back.ask() }
    return back
}

/** For you's rows by key, top to bottom: [all] as the list draws them, [withCards] as a [RowPick] counts them. */
private class RowKeys(val all: List<String>, val withCards: List<String>) {
    /** Whether the top row is still a place held by a skeleton, which takes the opening Highlight. */
    val topIsPlace get() = all.isNotEmpty() && all.first() !in withCards
}

private fun rowKeys(resume: List<ResumeItem>, rows: List<TitleRow<*>>, resumeLoading: Boolean): RowKeys {
    val cw = listOfNotNull(CONTINUE_WATCHING.takeIf { resume.isNotEmpty() })
    val cwPlace = listOfNotNull(CONTINUE_WATCHING.takeIf { resume.isNotEmpty() || resumeLoading })
    return RowKeys(cwPlace + rows.map { it.key }, cw + rows.filter { it.titles.isNotEmpty() }.map { it.key })
}

/** For you's rows with cards in them, as [restoreTarget] takes them. */
private fun <T> pickRows(
    keys: RowKeys,
    resume: List<ResumeItem>,
    rows: List<TitleRow<T>>,
    cover: (T) -> TitleCover,
): List<PickRow> = keys.withCards.map { key ->
    val items = if (key == CONTINUE_WATCHING) {
        resume.map { it.titleKey }
    } else {
        rows.first { it.key == key }.titles.map { cover(it).cardKey }
    }
    PickRow(key, items)
}

/** A cover's key wherever For you keeps one: its list, its row's memory, a remembered pick. */
private val TitleCover.cardKey get() = "$id"

private const val CONTINUE_WATCHING = "cw"
