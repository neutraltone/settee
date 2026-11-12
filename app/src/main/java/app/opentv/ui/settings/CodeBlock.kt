/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import app.opentv.ui.design.SetteeColors
import app.opentv.ui.design.SetteeShapes
import app.opentv.ui.design.SetteeType
import app.opentv.ui.design.Text

/**
 * The one thing in a pane that isn't a row: something to read off the TV and type or scan on
 * another device, such as Manage on phone or laptop's address, or the code while sharing. It can't
 * take the Highlight, so the remote goes straight past it to the rows below.
 */
@Composable
internal fun CodeBlock(
    lead: String,
    code: String,
    modifier: Modifier = Modifier,
    note: String? = null,
    beside: @Composable () -> Unit = {},
) {
    Row(
        modifier
            .testTag(SettingsTags.CODE_BLOCK)
            .fillMaxWidth()
            .background(SetteeColors.Surface, SetteeShapes.Card)
            .padding(BLOCK_PADDING),
        horizontalArrangement = Arrangement.spacedBy(BLOCK_PADDING),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        beside()
        Column(verticalArrangement = Arrangement.spacedBy(LINE_GAP)) {
            Text(lead, SetteeType.Body, color = SetteeColors.QuietText)
            Text(code, SetteeType.ScreenTitle)
            note?.let { Text(it, SetteeType.Body, color = SetteeColors.QuietText) }
        }
    }
}

private val BLOCK_PADDING = 20.dp
private val LINE_GAP = 8.dp
