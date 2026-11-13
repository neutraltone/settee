/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv.ui.channels

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.opentv.R
import app.opentv.data.model.Programme
import app.opentv.data.model.shownName
import app.opentv.ui.ChannelsViewModel
import app.opentv.ui.LocalRailReturn
import app.opentv.ui.design.HighlightStyle
import app.opentv.ui.design.HighlightSurface
import app.opentv.ui.design.SetteeColors
import app.opentv.ui.design.SetteeShapes
import app.opentv.ui.design.SetteeType
import app.opentv.ui.design.Text
import app.opentv.ui.options.OptionsSubject
import app.opentv.ui.options.RowHighlight
import app.opentv.ui.options.openOptionsOn
import app.opentv.ui.options.rememberRowHighlight
import coil.compose.AsyncImage
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.launch

/**
 * The guide (docs/LOOK.md, Live TV): channels down the left, a scrolling timeline to the right, each
 * programme a square cell as wide as it runs, with the times in a header above a strong rule. The
 * programme on now is bold and later ones are quiet.
 *
 * OK on a channel calls [onWatch]; OK on a programme calls [onProgramme]. Holding Select on a channel
 * opens its options, which is where Favourite is, so each cell has one target. Wherever the
 * Highlight lands, [onHighlight] says on what: the channel and, on a programme, which one.
 *
 * The day runs from [windowStartMillis] to [windowEndMillis]. Right past a row's last programme
 * calls [onNextDay] and Left before its first calls [onPrevDay], when there's a day that way: the
 * guide pages through days rather than running into them. A row with nothing on that day pages on
 * from its channel. On the new day the Highlight lands on [landOnRow]'s first programme, or its last
 * with [landAtEnd], or its channel when it has none, then [onLanded] says it's done.
 *
 * Left from a channel goes back to now first, when the guide is on a later day ([onToNow]) or
 * scrolled on from now, and only opens the sidebar from there. A new [rowsFor] (a category picked)
 * starts at the top and at the day's start.
 *
 * ## How it lays out without a custom Layout
 *
 * Every row and the time header share one [horizontalScroll] state, so scrolling any of them
 * scrolls all of them in lock-step and the columns stay time-aligned. Within a row, cells are
 * placed left to right at [MINUTE_DP] per minute ([layOutCells]), with spacers for gaps.
 *
 * A row builds only the cells on screen and one past each edge ([cellsToBuild]), plus its first
 * and last for paging a day to land on, and stands in for the rest with spacers of the same width,
 * so the strip is as wide as ever and every cell lands where it would have. Building the whole day
 * made every row a hundred or so cells, and the guide the Stick's most janky screen. Which to build is
 * worked out from the half hour the scroll is in, not the scroll itself, so a row rebuilds when
 * that changes rather than on every frame of a scroll.
 */
@Composable
fun GuideGrid(
    rows: List<ChannelsViewModel.Row>,
    windowStartMillis: Long,
    onWatch: (ChannelsViewModel.Row) -> Unit,
    modifier: Modifier = Modifier,
    windowEndMillis: Long = windowStartMillis + HOURS_IN_DAY * HOUR_MS,
    onHighlight: (ChannelsViewModel.Row, Programme?) -> Unit = { _, _ -> },
    onProgramme: (ChannelsViewModel.Row, Programme) -> Unit = { _, _ -> },
    dayLabel: String = "",
    dayOffset: Int = 0,
    onNextDay: ((ChannelsViewModel.Row) -> Unit)? = null,
    onPrevDay: ((ChannelsViewModel.Row) -> Unit)? = null,
    returnToChannelId: Long? = null,
    takeFocus: Boolean = true,
    onReturnToRow: (ChannelsViewModel.Row) -> Unit = {},
    landOnRow: Any? = null,
    landAtEnd: Boolean = false,
    onLanded: () -> Unit = {},
    rowsFor: Any? = null,
    onToNow: (() -> Unit)? = null,
    nowMillis: Long = System.currentTimeMillis(),
) {
    val scroll = rememberScrollState()
    // A fresh list for each category: one kept across them holds on to the old channel by key, and
    // opens the new category wherever it falls in it, often halfway down.
    val listState = remember(rowsFor) { LazyListState() }
    var lastRowsFor by remember { mutableStateOf(rowsFor) }
    LaunchedEffect(rowsFor) {
        if (rowsFor != lastRowsFor) scroll.scrollTo(0)
        lastRowsFor = rowsFor
    }
    val scope = rememberCoroutineScope()
    // Left from a channel: back to now when the guide has moved on from it, else on to the sidebar.
    val backToNow: () -> Boolean = {
        when {
            dayOffset > 0 && onToNow != null -> {
                onToNow()
                true
            }
            scroll.value > 0 -> {
                scope.launch { scroll.animateScrollTo(0) }
                true
            }
            else -> false
        }
    }
    val returnTo = rememberReturnToChannel(returnToChannelId, takeFocus, rows, listState, onReturnToRow)
    val highlight = rememberRowHighlight(rows, listState) { it.key }
    // A new day starts at its own beginning, or at its end when the viewer came back to it from
    // the next one, so the Highlight lands next to where it was.
    LaunchedEffect(dayOffset) { if (landAtEnd) scroll.scrollTo(scroll.maxValue) else scroll.scrollTo(0) }
    val strip = rememberStripState(scroll)

    Column(modifier.fillMaxSize().onSizeChanged { strip.gridWidthPx = it.width }) {
        TimeHeader(windowStartMillis, windowEndMillis, dayLabel, scroll, strip)
        LazyColumn(state = listState) {
            itemsIndexed(rows, key = { _, row -> row.key }) { index, row ->
                GuideRow(
                    row = row,
                    window = windowStartMillis..windowEndMillis,
                    nowMillis = nowMillis,
                    scroll = scroll,
                    strip = strip,
                    onWatch = { onWatch(row) },
                    onHighlight = { programme -> onHighlight(row, programme) },
                    onProgramme = { programme -> onProgramme(row, programme) },
                    onNextDay = onNextDay?.let { { it(row) } },
                    onPrevDay = onPrevDay?.let { { it(row) } },
                    focusRequester = returnTo.focusRequesterFor(index),
                    onLeft = backToNow,
                    highlight = highlight,
                    index = index,
                    land = when {
                        row.key != landOnRow -> null
                        landAtEnd -> Land.Last
                        else -> Land.First
                    },
                    onLanded = onLanded,
                )
            }
        }
    }
}

/**
 * Where the guide comes back to: the row of the channel last watched (its own or one of its
 * qualities), or the first row when there's none or the guide's filter leaves it out.
 */
private fun returnRowIndex(channelId: Long?, rows: List<ChannelsViewModel.Row>): Int =
    rows.indexOfFirst { row -> row.primary.id == channelId || row.variants.any { it.id == channelId } }
        .coerceAtLeast(0)

private class ReturnToChannel(private val index: Int?, private val focusRequester: FocusRequester) {
    fun focusRequesterFor(rowIndex: Int): FocusRequester? = focusRequester.takeIf { rowIndex == index }
}

/**
 * Once the rows arrive, scrolls [listState] to [channelId]'s row (or the first), tells
 * [onReturnToRow] which row that is and, if [takeFocus] is still true then, focuses it. Only the
 * first time: emptying and refilling the list later (a filter with nothing in it) keeps the guide's
 * own behaviour rather than jumping back.
 */
@Composable
private fun rememberReturnToChannel(
    channelId: Long?,
    takeFocus: Boolean,
    rows: List<ChannelsViewModel.Row>,
    listState: LazyListState,
    onReturnToRow: (ChannelsViewModel.Row) -> Unit,
): ReturnToChannel {
    val focusRequester = remember { FocusRequester() }
    var index by remember { mutableStateOf<Int?>(null) }
    var done by remember { mutableStateOf(false) }
    val mayTake by rememberUpdatedState(takeFocus)
    LaunchedEffect(rows.isNotEmpty()) {
        if (done || rows.isEmpty()) return@LaunchedEffect
        done = true
        val target = returnRowIndex(channelId, rows)
        index = target
        onReturnToRow(rows[target])
        listState.scrollToItem(target)
        // The row scrolled in is only laid out, and so focusable, a frame later.
        withFrameNanos {}
        if (mayTake) runCatching { focusRequester.requestFocus() }
    }
    // Remembered so the rows, which all read it, aren't invalidated on every recomposition.
    return remember(index) { ReturnToChannel(index, focusRequester) }
}

/** Which of a row's programmes takes the Highlight when the guide pages to another day. */
private enum class Land { First, Last }

/** The part of the strip, from its start, that rows build cells for: what's on screen and a little more. */
private data class StripView(val from: Dp, val width: Dp)

@Stable
private class StripState(scroll: ScrollState, private val density: Density, screenWidthPx: Int) {
    /** How wide the guide is laid out; the screen's width until it's measured, which is never less. */
    var gridWidthPx by mutableIntStateOf(screenWidthPx)

    // The half hour the scroll is in, rather than the scroll itself, so rows rebuild when that
    // changes and not on every frame of a scroll animation.
    private val halfHourPx = with(density) { HALF_HOUR_WIDTH.toPx() }
    private val slot by derivedStateOf { (scroll.value / halfHourPx).toInt() }

    /** From the start of [slot], a half hour wider than the strip on screen, so it covers it to the slot's end. */
    val view by derivedStateOf {
        val stripWidth = with(density) { gridWidthPx.toDp() } - CHANNEL_COLUMN
        StripView(from = HALF_HOUR_WIDTH * slot, width = stripWidth.coerceAtLeast(0.dp) + HALF_HOUR_WIDTH)
    }
}

@Composable
private fun rememberStripState(scroll: ScrollState): StripState {
    val density = LocalDensity.current
    val screenWidthDp = LocalConfiguration.current.screenWidthDp
    return remember(scroll, density) { StripState(scroll, density, with(density) { screenWidthDp.dp.roundToPx() }) }
}

@Composable
private fun TimeHeader(
    windowStartMillis: Long,
    windowEndMillis: Long,
    dayLabel: String,
    scroll: ScrollState,
    strip: StripState,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .height(HEADER_HEIGHT)
            .drawBehind {
                val stroke = STRONG_RULE.toPx()
                drawLine(
                    SetteeColors.QuietText,
                    Offset(0f, size.height - stroke / 2),
                    Offset(
                        size.width,
                        size.height - stroke / 2,
                    ),
                    stroke,
                )
            },
    ) {
        Box(Modifier.width(CHANNEL_COLUMN).fillMaxHeight(), contentAlignment = Alignment.CenterStart) {
            Text(dayLabel, SetteeType.Body, color = SetteeColors.Text, maxLines = 1)
        }
        Row(Modifier.horizontalScroll(scroll, enabled = false)) {
            // Only the labels over the strip on screen; spacers keep the header as wide as the rows.
            val view = strip.view
            val labels = ((windowEndMillis - windowStartMillis + HALF_HOUR_MS - 1) / HALF_HOUR_MS).toInt()
            val firstShown = (view.from / HALF_HOUR_WIDTH).toInt().coerceIn(0, labels)
            val lastShown = ((view.from + view.width) / HALF_HOUR_WIDTH).toInt().coerceAtMost(labels - 1)
            if (firstShown > 0) Spacer(Modifier.width(HALF_HOUR_WIDTH * firstShown))
            for (i in firstShown..lastShown) {
                Box(
                    Modifier.width(HALF_HOUR_WIDTH).fillMaxHeight().padding(start = CELL_PADDING),
                    contentAlignment = Alignment.CenterStart,
                ) {
                    Text(
                        clockFormat.format(Date(windowStartMillis + i * HALF_HOUR_MS)),
                        SetteeType.Body,
                        color = SetteeColors.QuietText,
                    )
                }
            }
            val after = labels - maxOf(lastShown + 1, firstShown)
            if (after > 0) Spacer(Modifier.width(HALF_HOUR_WIDTH * after))
        }
    }
}

@Composable
private fun GuideRow(
    row: ChannelsViewModel.Row,
    window: LongRange,
    nowMillis: Long,
    scroll: ScrollState,
    strip: StripState,
    onWatch: () -> Unit,
    onHighlight: (Programme?) -> Unit,
    onProgramme: (Programme) -> Unit,
    onNextDay: (() -> Unit)?,
    onPrevDay: (() -> Unit)?,
    focusRequester: FocusRequester?,
    onLeft: () -> Boolean,
    highlight: RowHighlight,
    index: Int,
    land: Land?,
    onLanded: () -> Unit,
) {
    val shown = remember(row.programmes, window) {
        row.programmes.filter { it.endUtcMillis > window.first && it.startUtcMillis < window.last }
    }
    val spans = remember(shown, window) { layOutCells(shown, window.first, window.last) }
    val channel = remember { FocusRequester() }
    val first = remember { FocusRequester() }
    val last = remember { FocusRequester() }
    // Paging a day, the window moves first and the programmes follow: land only once they're the new
    // day's, or the Highlight goes to a cell about to be replaced. A row with nothing on that day
    // lands on its channel, rather than losing the Highlight with the cell it was on.
    val forThisDay = row.programmes.all { it.endUtcMillis > window.first && it.startUtcMillis < window.last }
    if (land != null && forThisDay) {
        LaunchedEffect(land, shown) {
            withFrameNanos {}
            val target = when {
                shown.isEmpty() -> channel
                land == Land.Last -> last
                else -> first
            }
            runCatching { target.requestFocus() }
            onLanded()
        }
    }
    Row(
        Modifier
            .fillMaxWidth()
            .height(ROW_HEIGHT)
            .drawBehind {
                drawLine(
                    SetteeColors.Rule,
                    Offset(0f, size.height - 0.5f),
                    Offset(size.width, size.height - 0.5f),
                    RULE.toPx(),
                )
            },
    ) {
        ChannelCell(
            row = row,
            onWatch = onWatch,
            onHighlight = { onHighlight(null) },
            focusRequester = focusRequester,
            self = channel,
            // With nothing on this day there's no last programme to page on from, so the channel does.
            onRight = onNextDay.takeIf { shown.isEmpty() },
            onLeft = onLeft,
            highlight = highlight,
            index = index,
        )
        Row(Modifier.horizontalScroll(scroll)) {
            if (shown.isEmpty()) {
                Box(
                    Modifier.width(widthFor(window.first, window.last)).fillMaxHeight(),
                    contentAlignment = Alignment.CenterStart,
                ) {
                    Text(
                        stringResource(R.string.guide_no_info),
                        SetteeType.Body,
                        color = SetteeColors.QuietText,
                        modifier = Modifier.padding(start = CELL_PADDING),
                    )
                }
            } else {
                ProgrammeCells(
                    shown = shown,
                    spans = spans,
                    strip = strip,
                    nowMillis = nowMillis,
                    onHighlight = onHighlight,
                    onProgramme = onProgramme,
                    onNextDay = onNextDay,
                    onPrevDay = onPrevDay,
                    first = first,
                    last = last,
                )
            }
        }
    }
}

@Composable
private fun ChannelCell(
    row: ChannelsViewModel.Row,
    onWatch: () -> Unit,
    onHighlight: () -> Unit,
    focusRequester: FocusRequester?,
    self: FocusRequester,
    onRight: (() -> Unit)?,
    onLeft: () -> Boolean,
    highlight: RowHighlight,
    index: Int,
) {
    val railReturn = LocalRailReturn.current
    HighlightSurface(
        onClick = onWatch,
        style = HighlightStyle.Cell,
        onOptions = openOptionsOn(OptionsSubject.OfChannel(row.channel, onWatch = onWatch)),
        modifier = Modifier
            .then(if (focusRequester != null) Modifier.focusRequester(focusRequester) else Modifier)
            .then(highlight.modifier(index, row.key))
            .focusRequester(self)
            .onFocusChanged {
                if (it.isFocused) {
                    railReturn.target = self
                    onHighlight()
                }
            }
            .onPreviewKeyEvent { event ->
                if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                when (event.key) {
                    Key.DirectionRight -> onRight?.invoke() != null
                    Key.DirectionLeft -> onLeft()
                    else -> false
                }
            }
            .width(CHANNEL_COLUMN)
            .fillMaxHeight(),
    ) { highlighted ->
        Row(Modifier.fillMaxSize().padding(horizontal = CELL_PADDING), verticalAlignment = Alignment.CenterVertically) {
            AsyncImage(
                model = row.primary.logoUrl,
                contentDescription = null,
                modifier = Modifier.size(LOGO).clip(SetteeShapes.Cell),
            )
            Spacer(Modifier.width(CELL_PADDING))
            Text(
                row.primary.shownName,
                SetteeType.RowTitle,
                color = if (highlighted) SetteeColors.OnAccent else SetteeColors.Text,
                maxLines = 1,
            )
        }
    }
}

/**
 * [shown], the row's programmes in the window, laid out at [spans]: the ones [strip]'s view covers built as
 * cells and the rest spacers. [first] and [last] are for landing on.
 *
 * Beyond those on screen, the row's first and last are always built, since paging a day lands on
 * one of them wherever the strip is scrolled, and so is the one last highlighted, so the Highlight
 * isn't lost with its cell (the sidebar comes back to it).
 */
@Composable
private fun ProgrammeCells(
    shown: List<Programme>,
    spans: List<CellSpan>,
    strip: StripState,
    nowMillis: Long,
    onHighlight: (Programme?) -> Unit,
    onProgramme: (Programme) -> Unit,
    onNextDay: (() -> Unit)?,
    onPrevDay: (() -> Unit)?,
    first: FocusRequester,
    last: FocusRequester,
) {
    // By start rather than index, so a guide refresh that adds or drops a programme keeps the same one.
    var highlightedStart by remember { mutableLongStateOf(Long.MIN_VALUE) }
    val highlighted = shown.indexOfFirst { it.startUtcMillis == highlightedStart }
    // Read here, not in GuideGrid, so crossing a half hour recomposes the rows' cells and no more.
    val view = strip.view
    val built = (cellsToBuild(spans, view.from, view.width) + listOf(0, shown.lastIndex, highlighted))
        .filter { it in shown.indices }
        .toSortedSet()
    var x = 0.dp
    built.forEach { i ->
        val programme = shown[i]
        val span = spans[i]
        // Keyed, so as the built cells move along the row each keeps its own state, focus included,
        // rather than one slot passing it to whichever programme lands there next.
        key(programme.startUtcMillis) {
            if (span.x > x) Spacer(Modifier.width(span.x - x))
            ProgrammeCell(
                programme = programme,
                width = span.width,
                isNow = programme.isLiveAt(nowMillis),
                onHighlight = {
                    highlightedStart = programme.startUtcMillis
                    onHighlight(programme)
                },
                onClick = { onProgramme(programme) },
                onPastEnd = onNextDay.takeIf { i == shown.lastIndex },
                onBeforeStart = onPrevDay.takeIf { i == 0 },
                landing = listOfNotNull(first.takeIf { i == 0 }, last.takeIf { i == shown.lastIndex }),
            )
        }
        x = span.end
    }
    val stripEnd = spans.last().end
    if (stripEnd > x) Spacer(Modifier.width(stripEnd - x))
}

@Composable
private fun ProgrammeCell(
    programme: Programme,
    width: Dp,
    isNow: Boolean,
    onHighlight: () -> Unit,
    onClick: () -> Unit,
    onPastEnd: (() -> Unit)?,
    onBeforeStart: (() -> Unit)?,
    landing: List<FocusRequester>,
) {
    val railReturn = LocalRailReturn.current
    val self = remember { FocusRequester() }
    HighlightSurface(
        onClick = onClick,
        style = HighlightStyle.Cell,
        modifier = Modifier
            .then(landing.fold(Modifier as Modifier) { m, requester -> m.focusRequester(requester) })
            .focusRequester(self)
            .onFocusChanged {
                if (it.isFocused) {
                    railReturn.target = self
                    onHighlight()
                }
            }
            .onPreviewKeyEvent { event ->
                if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                val turn = when (event.key) {
                    Key.DirectionRight -> onPastEnd
                    Key.DirectionLeft -> onBeforeStart
                    else -> null
                } ?: return@onPreviewKeyEvent false
                turn()
                true
            }
            .width(width)
            .fillMaxHeight()
            .drawBehind { drawLine(SetteeColors.Rule, Offset(0f, 0f), Offset(0f, size.height), RULE.toPx()) },
    ) { highlighted ->
        Text(
            programme.title,
            if (isNow) SetteeType.RowTitle else SetteeType.Body,
            color = when {
                highlighted -> SetteeColors.OnAccent
                isNow -> SetteeColors.Text
                else -> SetteeColors.QuietText
            },
            maxLines = 2,
            modifier = Modifier.align(Alignment.CenterStart).padding(horizontal = CELL_PADDING),
        )
    }
}

private const val HOURS_IN_DAY = 24
private const val HOUR_MS = 60 * 60 * 1000L
private const val HALF_HOUR_MS = 30 * 60 * 1000L
private val CHANNEL_COLUMN = 220.dp
private val ROW_HEIGHT = 56.dp
private val HEADER_HEIGHT = 32.dp
private val LOGO = 32.dp
private val CELL_PADDING = 10.dp
private val RULE = 1.dp
private val STRONG_RULE = 2.dp
private val HALF_HOUR_WIDTH: Dp = (30 * MINUTE_DP).dp

private val clockFormat = SimpleDateFormat("HH:mm", Locale.getDefault())

/** UTC in the database, device zone on screen. Converted here and nowhere else. */
internal fun formatTime(utcMillis: Long): String = clockFormat.format(Date(utcMillis))
