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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
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
 * A panel down the right of the screen, over what's there, for a choice or an item's details: the
 * same family as the player's subtitles and audio panels. Back calls [onBack]; there's no Cancel.
 * The Highlight starts on its first row.
 *
 * It's a window of its own, so it takes Back and the remote before the screen under it does. That
 * window isn't dimmed: LOOK.md has no see-through shades, and the panel's solid background is
 * enough to set it apart.
 */
@Composable
fun SidePanel(onBack: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    Dialog(
        onDismissRequest = onBack,
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false),
    ) {
        val window = (LocalView.current.parent as? DialogWindowProvider)?.window
        LaunchedEffect(window) { window?.setDimAmount(0f) }
        val rows = remember { FocusRequester() }
        // Asked from inside the dialog: its window composes after the caller's, and a FocusRequester
        // asked before it's attached throws.
        LaunchedEffect(Unit) {
            withFrameNanos {}
            runCatching { rows.requestFocus() }
        }
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.CenterEnd) {
            Column(
                Modifier
                    .width(SidePanelWidth)
                    .fillMaxHeight()
                    .background(SetteeColors.Surface)
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = PANEL_PADDING, vertical = SetteeSpace.ScreenTopBottom)
                    .focusRequester(rows)
                    .focusGroup(),
                verticalArrangement = Arrangement.spacedBy(ROW_GAP),
                content = content,
            )
        }
    }
}

/**
 * One row of a [SidePanel]: [label], with the current [value] beside it when the row opens a
 * choice, or a tick when it's the [ticked] one of a choice.
 */
@Composable
fun PanelRow(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    value: String? = null,
    ticked: Boolean = false,
) {
    HighlightSurface(
        onClick = onClick,
        style = HighlightStyle.Cell,
        modifier = modifier.fillMaxWidth().semantics { selected = ticked },
    ) { highlighted ->
        val quiet = if (highlighted) SetteeColors.OnAccent else SetteeColors.QuietText
        Row(Modifier.fillMaxWidth().padding(ROW_PADDING), verticalAlignment = Alignment.CenterVertically) {
            Text(
                label,
                SetteeType.Body,
                color = if (highlighted) SetteeColors.OnAccent else SetteeColors.Text,
                maxLines = 1,
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

/** How wide a [SidePanel] is. */
val SidePanelWidth = 400.dp

private val PANEL_PADDING = 24.dp
private val ROW_GAP = 2.dp
private val ROW_PADDING = 12.dp
private const val TICK = "✓"
