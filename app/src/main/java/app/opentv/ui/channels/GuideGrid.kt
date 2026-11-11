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
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
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
import androidx.compose.ui.res.stringResource
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

/**
 * The guide (docs/LOOK.md, Live TV): channels down the left, a scrolling timeline to the right, each
 * programme a square cell as wide as it runs, with the times in a header above a strong rule. The
 * programme on now is bold and later ones are quiet.
 *
 * OK on a channel calls [onWatch]; OK on a programme calls [onProgramme]. Holding Select on a channel
 * opens its options, which is where Favourite is, so each cell has one target. Wherever the
 * Highlight lands, [onHighlight] says on what: the channel and, on a programme, which one.
 *
 * Right past a row's last programme calls [onNextDay] and Left before its first calls [onPrevDay],
 * when there's a day that way: the guide pages through days rather than running into them.
 *
 * ## How it lays out without a custom Layout
 *
 * Every row and the time header share one [horizontalScroll] state, so scrolling any of them
 * scrolls all of them in lock-step and the columns stay time-aligned. Within a row, cells are
 * placed left to right at [MINUTE_DP] per minute, with spacers for gaps. No absolute positioning
 * and no measuring pass, which is cheap enough for a lazy list of thousands of channels on a Stick.
 */
@Composable
fun GuideGrid(
    rows: List<ChannelsViewModel.Row>,
    windowStartMillis: Long,
    onWatch: (ChannelsViewModel.Row) -> Unit,
    modifier: Modifier = Modifier,
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
) {
    val scroll = rememberScrollState()
    val listState = rememberLazyListState()
    val returnTo = rememberReturnToChannel(returnToChannelId, takeFocus, rows, listState, onReturnToRow)
    val highlight = rememberRowHighlight(rows, listState) { it.key }
    val now = System.currentTimeMillis()
    // A new day starts at its own beginning, or at its end when the viewer came back to it from
    // the next one, so the Highlight lands next to where it was.
    LaunchedEffect(dayOffset) { if (landAtEnd) scroll.scrollTo(scroll.maxValue) else scroll.scrollTo(0) }

    Column(modifier.fillMaxSize()) {
        TimeHeader(windowStartMillis, dayLabel, scroll)
        LazyColumn(state = listState) {
            itemsIndexed(rows, key = { _, row -> row.key }) { index, row ->
                GuideRow(
                    row = row,
                    windowStartMillis = windowStartMillis,
                    nowMillis = now,
                    scroll = scroll,
                    onWatch = { onWatch(row) },
                    onHighlight = { programme -> onHighlight(row, programme) },
                    onProgramme = { programme -> onProgramme(row, programme) },
                    onNextDay = onNextDay?.let { { it(row) } },
                    onPrevDay = onPrevDay?.let { { it(row) } },
                    focusRequester = returnTo.focusRequesterFor(index),
                    highlight = highlight,
                    index = index,
                    land = when {
                        row.key != landOnRow -> null
                        landAtEnd -> Land.Last
                        else -> Land.First
                    },
                    dayOffset = dayOffset,
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

@Composable
private fun TimeHeader(windowStartMillis: Long, dayLabel: String, scroll: ScrollState) {
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
            repeat(HOURS_IN_WINDOW * 2) { i ->
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
        }
    }
}

@Composable
private fun GuideRow(
    row: ChannelsViewModel.Row,
    windowStartMillis: Long,
    nowMillis: Long,
    scroll: ScrollState,
    onWatch: () -> Unit,
    onHighlight: (Programme?) -> Unit,
    onProgramme: (Programme) -> Unit,
    onNextDay: (() -> Unit)?,
    onPrevDay: (() -> Unit)?,
    focusRequester: FocusRequester?,
    highlight: RowHighlight,
    index: Int,
    land: Land?,
    dayOffset: Int,
) {
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
        ChannelCell(row, onWatch, { onHighlight(null) }, focusRequester, highlight, index)
        Row(Modifier.horizontalScroll(scroll)) {
            val programmes = row.programmes
            if (programmes.isEmpty()) {
                Box(
                    Modifier.width(HALF_HOUR_WIDTH * HOURS_IN_WINDOW * 2).fillMaxHeight(),
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
                    programmes,
                    windowStartMillis,
                    nowMillis,
                    onHighlight,
                    onProgramme,
                    onNextDay,
                    onPrevDay,
                    land,
                    dayOffset,
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
    highlight: RowHighlight,
    index: Int,
) {
    val railReturn = LocalRailReturn.current
    val self = remember { FocusRequester() }
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

@Composable
private fun ProgrammeCells(
    programmes: List<Programme>,
    windowStartMillis: Long,
    nowMillis: Long,
    onHighlight: (Programme?) -> Unit,
    onProgramme: (Programme) -> Unit,
    onNextDay: (() -> Unit)?,
    onPrevDay: (() -> Unit)?,
    land: Land?,
    dayOffset: Int,
) {
    val windowEnd = windowStartMillis + HOURS_IN_WINDOW * 60 * 60 * 1000L
    val shown = programmes.filter { it.endUtcMillis > windowStartMillis && it.startUtcMillis < windowEnd }
    var cursor = windowStartMillis
    // A programme almost over (or a very short one) would otherwise be a sliver too thin to read.
    // Each cell is floored at [MIN_CELL_WIDTH], and the borrowed width is repaid out of the gaps
    // that follow, so everything downstream stays aligned with the time header.
    var debt = 0.dp
    shown.forEachIndexed { i, programme ->
        val start = programme.startUtcMillis.coerceAtLeast(windowStartMillis)
        val gap = widthFor(cursor, start)
        val repaid = minOf(debt, gap)
        debt -= repaid
        if (gap - repaid > 0.dp) Spacer(Modifier.width(gap - repaid))
        val end = programme.endUtcMillis.coerceAtMost(windowEnd)
        val trueWidth = widthFor(start, end)
        val drawnWidth = maxOf(trueWidth, MIN_CELL_WIDTH)
        debt += drawnWidth - trueWidth
        ProgrammeCell(
            programme = programme,
            width = drawnWidth,
            isNow = programme.isLiveAt(nowMillis),
            onHighlight = { onHighlight(programme) },
            onClick = { onProgramme(programme) },
            onPastEnd = onNextDay.takeIf { i == shown.lastIndex },
            onBeforeStart = onPrevDay.takeIf { i == 0 },
            landHere = (land == Land.First && i == 0) || (land == Land.Last && i == shown.lastIndex),
            dayOffset = dayOffset,
        )
        cursor = end
    }
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
    landHere: Boolean,
    dayOffset: Int,
) {
    val railReturn = LocalRailReturn.current
    val self = remember { FocusRequester() }
    if (landHere) {
        LaunchedEffect(dayOffset) {
            withFrameNanos {}
            runCatching { self.requestFocus() }
        }
    }
    HighlightSurface(
        onClick = onClick,
        style = HighlightStyle.Cell,
        modifier = Modifier
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

/** Minutes between two instants, as guide width. Never negative. */
private fun widthFor(fromMillis: Long, toMillis: Long): Dp {
    val minutes = ((toMillis - fromMillis) / 60_000L).coerceAtLeast(0)
    return (minutes * MINUTE_DP).dp
}

// A day across at 4 dp a minute is about 5800 dp of strip, which a Stick scrolls happily.
private const val MINUTE_DP = 4f
private const val HOURS_IN_WINDOW = 24
private const val HALF_HOUR_MS = 30 * 60 * 1000L
private val CHANNEL_COLUMN = 220.dp
private val ROW_HEIGHT = 56.dp
private val HEADER_HEIGHT = 32.dp
private val LOGO = 32.dp
private val CELL_PADDING = 10.dp
private val RULE = 1.dp
private val STRONG_RULE = 2.dp

// Floor for a cell so a nearly finished or very short programme still shows its name.
private val MIN_CELL_WIDTH = 72.dp
private val HALF_HOUR_WIDTH: Dp = (30 * MINUTE_DP).dp

private val clockFormat = SimpleDateFormat("HH:mm", Locale.getDefault())

/** UTC in the database, device zone on screen. Converted here and nowhere else. */
internal fun formatTime(utcMillis: Long): String = clockFormat.format(Date(utcMillis))
