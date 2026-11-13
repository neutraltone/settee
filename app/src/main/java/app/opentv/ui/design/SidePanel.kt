/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv.ui.design

import androidx.compose.foundation.background
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider

/**
 * A Side Panel: down the right of the screen, over what's there, for a choice, an item's details, or
 * a question before acting. Every question, choice and notice in Settee opens in one (docs/LOOK.md,
 * Side Panel). Back calls [onBack]; there's no Cancel.
 * The Highlight starts on [startOn] when given, or else on the ticked [PanelRow] of a choice, or
 * else on the first row.
 *
 * It's a window of its own, so it takes Back and the remote before the screen under it does. That
 * window isn't dimmed: LOOK.md has no see-through shades, and the panel's solid background is
 * enough to set it apart.
 */
@Composable
fun SidePanel(onBack: () -> Unit, startOn: FocusRequester? = null, content: @Composable ColumnScope.() -> Unit) {
    SidePanelWindow(onBack, startOn, scrolls = true, content)
}

@Composable
private fun SidePanelWindow(
    onBack: () -> Unit,
    startOn: FocusRequester?,
    scrolls: Boolean,
    content: @Composable ColumnScope.() -> Unit,
) {
    Dialog(
        onDismissRequest = onBack,
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false),
    ) {
        val window = (LocalView.current.parent as? DialogWindowProvider)?.window
        LaunchedEffect(window) { window?.setDimAmount(0f) }
        val rows = remember { FocusRequester() }
        val ticked = remember { FocusRequester() }
        // Asked from inside the dialog: its window composes after the caller's, and a FocusRequester
        // asked before it's attached throws.
        LaunchedEffect(Unit) {
            withFrameNanos {}
            if (startOn != null) {
                runCatching { startOn.requestFocus() }
            } else if (runCatching { ticked.requestFocus() }.getOrNull() != true) {
                runCatching { rows.requestFocus() }
            }
        }
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.CenterEnd) {
            CompositionLocalProvider(LocalTickedRow provides ticked) {
                PanelColumn(rows, scrolls, content)
            }
        }
    }
}

@Composable
private fun PanelColumn(rows: FocusRequester, scrolls: Boolean, content: @Composable ColumnScope.() -> Unit) {
    Column(
        Modifier
            .width(SidePanelWidth)
            .fillMaxHeight()
            .background(SetteeColors.Surface)
            .then(if (scrolls) Modifier.verticalScroll(rememberScrollState()) else Modifier)
            .padding(horizontal = PANEL_PADDING, vertical = SetteeSpace.ScreenTopBottom)
            .focusRequester(rows)
            .focusGroup(),
        verticalArrangement = Arrangement.spacedBy(ROW_GAP),
        content = content,
    )
}

/** The ticked row of the open [SidePanel], where a choice's Highlight starts. */
private val LocalTickedRow = staticCompositionLocalOf<FocusRequester?> { null }

/**
 * One row of a [SidePanel]: [label], with the current [value] beside it when the row opens a
 * choice, or a tick when it's the [ticked] one of a choice. A row not [enabled] is greyed out, and the
 * Highlight passes over it.
 */
@Composable
fun PanelRow(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    value: String? = null,
    ticked: Boolean = false,
    enabled: Boolean = true,
) {
    val startHere = LocalTickedRow.current?.takeIf { ticked }
    HighlightSurface(
        onClick = onClick,
        style = HighlightStyle.Cell,
        enabled = enabled,
        modifier = modifier
            .fillMaxWidth()
            .semantics { selected = ticked }
            .then(if (startHere != null) Modifier.focusRequester(startHere) else Modifier),
    ) { highlighted ->
        val quiet = if (highlighted) SetteeColors.OnAccent else SetteeColors.QuietText
        Row(Modifier.fillMaxWidth().padding(ROW_PADDING), verticalAlignment = Alignment.CenterVertically) {
            // Wraps: a label can name a programme or series, and cutting it off hides which one.
            Text(
                label,
                SetteeType.Body,
                color = when {
                    highlighted -> SetteeColors.OnAccent
                    enabled -> SetteeColors.Text
                    else -> SetteeColors.QuietText
                },
                modifier = Modifier.weight(1f),
            )
            value?.let { Text(it, SetteeType.Body, color = quiet, maxLines = 1) }
            if (ticked) {
                Text(
                    TICK,
                    SetteeType.Body,
                    color = if (highlighted) SetteeColors.OnAccent else SetteeColors.Accent,
                )
            }
        }
    }
}

/** What a [SidePanel] is about, such as the item whose details or options it holds. */
@Composable
fun PanelTitle(text: String, modifier: Modifier = Modifier) {
    Text(text, SetteeType.Section, maxLines = 2, modifier = modifier.padding(bottom = ROW_PADDING))
}

/** A prompt's short explanation, under its [PanelTitle] and above its choices. */
@Composable
fun PanelBody(text: String, modifier: Modifier = Modifier) {
    Text(text, SetteeType.Body, modifier = modifier.padding(bottom = ROW_PADDING))
}

/**
 * A [SidePanel] for a notice whose [notes] can be long, such as release notes: [title], then [body]
 * if there is one, the notes, and the choices under them, [main] first and then [others].
 *
 * The notes never take the Highlight. It starts on [main], where Up and Down scroll the notes, so
 * the main choice is always one press away however far the viewer has read. Once the notes reach
 * their end, Down carries on to [others] as in any panel, and Up from there comes back to [main].
 */
@Composable
fun NotesPanel(
    title: String,
    main: String,
    onMain: () -> Unit,
    onBack: () -> Unit,
    body: String? = null,
    others: @Composable ColumnScope.() -> Unit = {},
    notes: @Composable ColumnScope.() -> Unit,
) {
    val scroll = rememberScrollState()
    val mainRow = remember { FocusRequester() }
    SidePanelWindow(onBack, startOn = mainRow, scrolls = false) {
        PanelTitle(title)
        body?.let { PanelBody(it) }
        // Not filling: short notes leave the choices right under them, long ones stop where the
        // choices begin and scroll.
        Column(
            Modifier.weight(1f, fill = false).fillMaxWidth().verticalScroll(scroll).padding(bottom = ROW_PADDING),
            content = notes,
        )
        PanelRow(main, onClick = onMain, modifier = Modifier.focusRequester(mainRow).dpadScrolls(scroll))
        others()
    }
}

/**
 * Asks before something that can't be undone, such as removing a provider: [confirm] above [keep],
 * with the Highlight starting on [keep], so a stray OK changes nothing. Back keeps it too. A prompt
 * that needs explaining has a [title] and a short [body] above the choices.
 */
@Composable
fun ConfirmPanel(
    confirm: String,
    keep: String,
    onConfirm: () -> Unit,
    onKeep: () -> Unit,
    title: String? = null,
    body: String? = null,
) {
    val keepRow = remember { FocusRequester() }
    SidePanel(onBack = onKeep, startOn = keepRow) {
        title?.let { PanelTitle(it) }
        body?.let { PanelBody(it) }
        PanelRow(confirm, onClick = onConfirm)
        PanelRow(keep, onClick = onKeep, modifier = Modifier.focusRequester(keepRow))
    }
}

/** How wide a [SidePanel] is. */
val SidePanelWidth = 400.dp

private val PANEL_PADDING = 24.dp
private val ROW_GAP = 2.dp
private val ROW_PADDING = 12.dp
private const val TICK = "✓"
