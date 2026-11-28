/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv.ui.design

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp

/**
 * One of a row of choices, such as a show's seasons, where one is [selected]. The selected pill
 * keeps a Raised fill and coral text when the Highlight is elsewhere, so the row still says which
 * is chosen; the rest rest on Surface in Quiet text. An [icon] before the label, read out as
 * [iconDescription], says something about that choice, such as a season coming from add-ons.
 */
@Composable
fun Pill(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    onOptions: (() -> Unit)? = null,
    icon: ImageVector? = null,
    iconDescription: String? = null,
) {
    HighlightSurface(
        onClick = onClick,
        style = if (selected) Selected else Unselected,
        modifier = modifier.semantics { this.selected = selected },
        onOptions = onOptions,
    ) { highlighted ->
        val colour = when {
            highlighted -> SetteeColors.OnAccent
            selected -> SetteeColors.Accent
            else -> SetteeColors.QuietText
        }
        Row(
            Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (icon != null) Icon(icon, iconDescription, Modifier.size(20.dp), tint = colour)
            Text(label, style = SetteeType.Body, color = colour, maxLines = 1)
        }
    }
}

private val Selected = HighlightStyle(
    resting = HighlightLook(SetteeShapes.Pill, fill = SetteeColors.Raised),
    highlighted = HighlightLook(SetteeShapes.Pill, fill = SetteeColors.Accent),
)

private val Unselected = HighlightStyle(
    resting = HighlightLook(SetteeShapes.Pill, fill = SetteeColors.Surface),
    highlighted = HighlightLook(SetteeShapes.Pill, fill = SetteeColors.Accent),
)
