/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv.ui.design

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.toggleableState
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.unit.dp

/**
 * One row of a settings list, with one target: OK, or a tap, calls [onClick]. What sits at its end
 * says what OK does:
 *
 * - [switch]: a [Switch] in that state, which OK flips.
 * - [value]: the current choice and a chevron, for a row that opens a choice or an entry screen.
 * - [opens]: a chevron alone, for a row that opens a screen of its own.
 * - none: an action, or an item in a list.
 *
 * [note] is a quiet line under the label, such as which Profile a setting belongs to, or an error's
 * sentence; [detail] is that error's code, status and format, in a smaller quiet line (LOOK.md,
 * Voice), never left out where an error has one. [onOptions]
 * gives the row options (hold Select), for an item with more to do than its main thing.
 */
@Composable
fun ListRow(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    value: String? = null,
    note: String? = null,
    detail: String? = null,
    switch: Boolean? = null,
    opens: Boolean = value != null,
    onOptions: (() -> Unit)? = null,
) {
    HighlightSurface(
        onClick = onClick,
        style = HighlightStyle.ListRow,
        onOptions = onOptions,
        modifier = modifier
            .fillMaxWidth()
            .semantics { if (switch != null) toggleableState = ToggleableState(switch) },
    ) { highlighted ->
        val text = if (highlighted) SetteeColors.OnAccent else SetteeColors.Text
        val quiet = if (highlighted) SetteeColors.OnAccent else SetteeColors.QuietText
        Row(
            Modifier.fillMaxWidth().heightIn(min = ROW_HEIGHT).padding(horizontal = SIDE, vertical = TOP_BOTTOM),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(label, SetteeType.RowTitle, color = text, maxLines = 2)
                note?.let { Text(it, SetteeType.Body, color = quiet, maxLines = NOTE_LINES) }
                detail?.takeIf { it.isNotBlank() }?.let {
                    Text(
                        it,
                        SetteeType.Body,
                        color = quiet,
                        maxLines = 1,
                        modifier = Modifier.testTag(ERROR_DETAIL_TAG),
                    )
                }
            }
            value?.let {
                Spacer(Modifier.width(SIDE))
                Text(it, SetteeType.Body, color = quiet, maxLines = 1)
            }
            if (opens) {
                Spacer(Modifier.width(GAP))
                Text(CHEVRON, SetteeType.RowTitle, color = quiet)
            }
            switch?.let {
                Spacer(Modifier.width(SIDE))
                Switch(on = it, highlighted = highlighted)
            }
        }
    }
}

/**
 * A switch's look, for a [ListRow] to show its state. It can't take the Highlight itself: the row
 * is the one target, and OK on the row flips it. On the row's coral fill a coral track would
 * vanish, so a highlighted switch draws in dark instead.
 */
@Composable
fun Switch(on: Boolean, highlighted: Boolean, modifier: Modifier = Modifier) {
    val track = when {
        highlighted -> SetteeColors.OnAccent
        on -> SetteeColors.Accent
        else -> SetteeColors.Raised
    }
    val thumb = when {
        highlighted && on -> SetteeColors.Accent
        on -> SetteeColors.Text
        else -> SetteeColors.QuietText
    }
    Box(
        modifier
            .size(width = TRACK_WIDTH, height = TRACK_HEIGHT)
            .background(track, SetteeShapes.Pill)
            .padding(THUMB_INSET),
        contentAlignment = if (on) Alignment.CenterEnd else Alignment.CenterStart,
    ) {
        Box(Modifier.size(TRACK_HEIGHT - THUMB_INSET * 2).background(thumb, SetteeShapes.Pill))
    }
}

// Room for a whole explanation beside a value, which a TV row has no other way to show.
private const val NOTE_LINES = 4
private val ROW_HEIGHT = 52.dp
private val SIDE = 10.dp
private val TOP_BOTTOM = 8.dp
private val GAP = 8.dp
private val TRACK_WIDTH = 44.dp
private val TRACK_HEIGHT = 24.dp
private val THUMB_INSET = 3.dp
private const val CHEVRON = "›"
