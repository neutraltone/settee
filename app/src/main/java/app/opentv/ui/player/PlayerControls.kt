/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv.ui.player

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import app.opentv.ui.design.SetteeColors
import app.opentv.ui.design.SetteeShapes
import app.opentv.ui.design.SetteeType
import app.opentv.ui.design.Text

/** A short message over the picture. It can't take focus, so it leaves the D-pad as it was. */
@Composable
internal fun PlayerNotice(message: String, modifier: Modifier = Modifier) {
    Text(
        message,
        SetteeType.RowTitle,
        textAlign = TextAlign.Center,
        modifier = modifier
            .padding(32.dp)
            .background(NoticeBackground, SetteeShapes.Card)
            .padding(horizontal = 24.dp, vertical = 12.dp),
    )
}

private val NoticeBackground = SetteeColors.Background.copy(alpha = 0.9f)
