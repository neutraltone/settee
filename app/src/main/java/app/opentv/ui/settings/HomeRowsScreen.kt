/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv.ui.settings

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DragHandle
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.opentv.R
import app.opentv.core.ServiceLocator
import app.opentv.data.repo.HomeArrangement
import app.opentv.data.repo.HomeRowKind
import app.opentv.ui.design.Icon
import app.opentv.ui.design.ListRow
import app.opentv.ui.design.PanelRow
import app.opentv.ui.design.PanelTitle
import app.opentv.ui.design.SetteeColors
import app.opentv.ui.design.SetteeSpace
import app.opentv.ui.design.SetteeType
import app.opentv.ui.design.SidePanel
import app.opentv.ui.design.Text
import kotlinx.coroutines.launch

/**
 * Settings → Display → Home rows: the active Profile's arrangement of Home (GLOSSARY: Home Row), a
 * screen of its own. Back returns to Display's row.
 */
@Composable
fun HomeRowsScreen(onBack: () -> Unit) {
    val settings = ServiceLocator.get(LocalContext.current).profileSettings
    val arrangement by settings.homeArrangement.collectAsState(initial = null)
    val scope = rememberCoroutineScope()
    val current = arrangement
    // Nothing until it's read, rather than the usual order flashing up before the Profile's own.
    if (current == null) {
        BackHandler(onBack = onBack)
        return
    }
    HomeRowsContent(current, onArrange = { scope.launch { settings.arrangeHome(it) } }, onBack = onBack)
}

/**
 * The Live Preview's switch, the Home Rows in [arrangement]'s order, then Restore the usual order.
 *
 * Select lifts a Home Row; Up and Down move it, stopping at the first and last Home Row; Select drops
 * it, saving through [onArrange], and Back puts it back where it was. Left and Right do nothing while
 * it's lifted, so the Highlight can't leave with a row in hand. Options on a row hides or shows it.
 * On touch, each row's grip drags it, and letting go saves.
 */
@Composable
internal fun HomeRowsContent(arrangement: HomeArrangement, onArrange: (HomeArrangement) -> Unit, onBack: () -> Unit) {
    // While a row is lifted, the order being tried out; saved only when it's dropped.
    var moving by remember { mutableStateOf<Moving?>(null) }
    var options by remember { mutableStateOf<HomeRowKind?>(null) }
    val shown = moving?.rows ?: arrangement.rows
    val requesters = remember { HomeRowKind.entries.associateWith { FocusRequester() } }
    val livePreviewRow = remember { FocusRequester() }

    LaunchedEffect(Unit) {
        withFrameNanos {}
        runCatching { livePreviewRow.requestFocus() }
    }
    // The lifted row keeps the Highlight wherever it goes.
    val lifted = moving?.row
    LaunchedEffect(lifted, shown) {
        if (lifted != null) runCatching { requesters.getValue(lifted).requestFocus() }
    }

    BackHandler(onBack = onBack)
    // Registered after the page's own, so with a row lifted Back puts it back rather than leaving.
    BackHandler(enabled = moving != null) { moving = null }

    fun move(by: Int) {
        val now = moving ?: return
        val from = now.rows.indexOf(now.row)
        val to = (from + by).coerceIn(0, now.rows.lastIndex)
        if (to != from) moving = now.copy(rows = now.rows.toMutableList().apply { add(to, removeAt(from)) })
    }

    fun drop() {
        val done = moving ?: return
        moving = null
        if (done.rows != arrangement.rows) onArrange(arrangement.copy(rows = done.rows))
    }

    val on = stringResource(R.string.settings_row_on)
    val off = stringResource(R.string.settings_row_off)
    val shownLabel = stringResource(R.string.home_rows_shown)
    val hiddenLabel = stringResource(R.string.home_rows_hidden)
    Column(
        Modifier
            .fillMaxSize()
            .background(SetteeColors.Background)
            .padding(horizontal = SetteeSpace.ScreenSide, vertical = SetteeSpace.ScreenTopBottom)
            .onPreviewKeyEvent { event ->
                if (moving == null) return@onPreviewKeyEvent false
                val by = when (event.key) {
                    Key.DirectionUp -> -1
                    Key.DirectionDown -> 1
                    Key.DirectionLeft, Key.DirectionRight -> 0
                    else -> return@onPreviewKeyEvent false
                }
                if (event.type == KeyEventType.KeyDown && by != 0) move(by)
                true
            }
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(ROW_GAP),
    ) {
        Text(stringResource(R.string.home_rows_title), SetteeType.ScreenTitle)
        val column = Modifier.widthIn(max = 900.dp)
        ListRow(
            stringResource(R.string.home_rows_live_preview),
            onClick = { if (moving == null) onArrange(arrangement.copy(livePreview = !arrangement.livePreview)) },
            switch = arrangement.livePreview,
            modifier = column.focusRequester(livePreviewRow),
        )
        shown.forEach { row ->
            key(row) {
                HomeRowItem(
                    row = row,
                    value = if (arrangement.isShown(row)) shownLabel else hiddenLabel,
                    lifted = row == lifted,
                    modifier = column.focusRequester(requesters.getValue(row)),
                    onSelect = {
                        when {
                            moving == null -> moving = Moving(row, arrangement.rows)
                            row == lifted -> drop()
                        }
                    },
                    onOptions = { if (moving == null) options = row },
                    drag = RowDrag(
                        start = { moving = Moving(row, arrangement.rows) },
                        move = ::move,
                        end = ::drop,
                    ),
                )
            }
        }
        ListRow(
            stringResource(R.string.home_rows_restore),
            onClick = { if (moving == null) onArrange(HomeArrangement.USUAL) },
            modifier = column,
        )
        if (moving != null) {
            Text(stringResource(R.string.home_rows_moving_hint), SetteeType.Body, color = SetteeColors.QuietText)
        }
    }

    options?.let { row ->
        SidePanel(onBack = { options = null }) {
            PanelTitle(stringResource(row.label))
            val isShown = arrangement.isShown(row)
            PanelRow(
                stringResource(R.string.home_rows_show_on_home),
                value = if (isShown) on else off,
                onClick = { onArrange(arrangement.withShown(row, !isShown)) },
            )
        }
    }
}

/** A row lifted to be moved, and the order it's in so far. */
private data class Moving(val row: HomeRowKind, val rows: List<HomeRowKind>)

/** What a finger dragging a row's grip does: lifts it, moves it a row at a time, and drops it. */
private class RowDrag(val start: () -> Unit, val move: (Int) -> Unit, val end: () -> Unit)

@Composable
private fun HomeRowItem(
    row: HomeRowKind,
    value: String,
    lifted: Boolean,
    modifier: Modifier,
    onSelect: () -> Unit,
    onOptions: () -> Unit,
    drag: RowDrag,
) {
    val gapPx = with(LocalDensity.current) { ROW_GAP.toPx() }
    var heightPx by remember { mutableFloatStateOf(0f) }
    // How far the finger has carried the row past the place it now has, drawn as an offset so the
    // row follows the finger while the others slide aside a whole row at a time.
    var offset by remember { mutableFloatStateOf(0f) }
    val currentDrag by rememberUpdatedState(drag)
    ListRow(
        stringResource(row.label),
        onClick = onSelect,
        value = value,
        opens = false,
        lifted = lifted,
        onOptions = onOptions,
        modifier = modifier
            .onSizeChanged { heightPx = it.height.toFloat() }
            .graphicsLayer { translationY = if (lifted) offset else 0f },
        leading = { highlighted ->
            Icon(
                Icons.Filled.DragHandle,
                contentDescription = null,
                tint = if (highlighted) SetteeColors.OnAccent else SetteeColors.QuietText,
                modifier = Modifier
                    .size(GRIP)
                    .testTag(homeRowGripTag(row))
                    .pointerInput(row) {
                        detectDragGestures(
                            onDragStart = {
                                offset = 0f
                                currentDrag.start()
                            },
                            onDragEnd = {
                                offset = 0f
                                currentDrag.end()
                            },
                            onDragCancel = {
                                offset = 0f
                                currentDrag.end()
                            },
                        ) { change, amount ->
                            change.consume()
                            offset += amount.y
                            // A row's height and the gap is one place; past half of it, the row takes
                            // the next place and the row there slides into the one it left.
                            val pitch = heightPx + gapPx
                            if (pitch <= 0f) return@detectDragGestures
                            while (offset < -pitch / 2) {
                                currentDrag.move(-1)
                                offset += pitch
                            }
                            while (offset > pitch / 2) {
                                currentDrag.move(1)
                                offset -= pitch
                            }
                        }
                    },
            )
        },
    )
}

/** The test tag on [row]'s grip, the handle a finger drags it by. */
internal fun homeRowGripTag(row: HomeRowKind) = "homeRowGrip:${row.key}"

/** What Home and this page call [this] row. */
private val HomeRowKind.label: Int
    get() = when (this) {
        HomeRowKind.CONTINUE_WATCHING -> R.string.vod_continue_watching
        HomeRowKind.RECENT_SHOWS -> R.string.home_recently_added_shows
        HomeRowKind.RECENT_MOVIES -> R.string.home_recently_added_movies
        HomeRowKind.FAVOURITE_SHOWS -> R.string.home_favourite_shows
        HomeRowKind.FAVOURITE_MOVIES -> R.string.home_favourite_movies
        HomeRowKind.FAVOURITE_CHANNELS -> R.string.home_favourite_channels
    }

private val ROW_GAP = 8.dp
private val GRIP = 24.dp
