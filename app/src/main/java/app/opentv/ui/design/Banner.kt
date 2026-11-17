/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv.ui.design

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp

/**
 * A line across the top of a screen about something the viewer has to put right, with one button to
 * go and do it. A coral edge marks it out from the rows below without shouting; it has no way to
 * dismiss it, since it goes when its cause does.
 */
@Composable
fun Banner(text: String, action: String, onAction: () -> Unit, modifier: Modifier = Modifier) {
    Row(
        modifier
            .fillMaxWidth()
            .clip(SetteeShapes.Card)
            .background(SetteeColors.Surface)
            .height(IntrinsicSize.Min),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.width(EDGE).fillMaxHeight().background(SetteeColors.Accent))
        Row(
            Modifier.weight(1f).padding(horizontal = SIDE, vertical = TOP_BOTTOM),
            horizontalArrangement = Arrangement.spacedBy(SIDE),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(text, SetteeType.Synopsis, modifier = Modifier.weight(1f), maxLines = 2)
            ActionButton(action, onAction)
        }
    }
}

private val EDGE = 4.dp
private val SIDE = 18.dp
private val TOP_BOTTOM = 10.dp
