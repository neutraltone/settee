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
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp

/**
 * A button: a pill on a Raised fill, solid coral with dark text while it has the Highlight (docs/LOOK.md).
 * One that isn't [enabled] has quiet text and the Highlight passes over it.
 */
@Composable
fun PillButton(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    enabled: Boolean = true,
) {
    HighlightSurface(
        onClick = onClick,
        style = HighlightStyle.Button,
        modifier = modifier,
        enabled = enabled,
    ) { highlighted ->
        val colour = when {
            highlighted -> SetteeColors.OnAccent
            enabled -> SetteeColors.Text
            else -> SetteeColors.QuietText
        }
        Row(
            Modifier.padding(horizontal = 22.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (icon != null) Icon(icon, contentDescription = null, tint = colour, modifier = Modifier.size(22.dp))
            Text(label, style = SetteeType.Body, color = colour, maxLines = 1)
        }
    }
}
