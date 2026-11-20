/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv.ui.settings

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.opentv.R
import app.opentv.ui.design.ActionButton
import app.opentv.ui.design.SetteeColors
import app.opentv.ui.design.SetteeSpace
import app.opentv.ui.design.SetteeType
import app.opentv.ui.design.Text
import app.opentv.ui.design.dpadScrolls
import app.opentv.update.startsFocused

/**
 * A screen of text opened from a Settings row, such as the font licences or the Diagnostics report:
 * its title, the text, and Done. Plain text can't take the Highlight, so Up and Down on Done scroll
 * the text, as in What's new. Back, like Done, returns to the row.
 */
@Composable
internal fun TextScreen(
    title: String,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    lineGap: Dp = 8.dp,
    content: @Composable ColumnScope.() -> Unit,
) {
    val scroll = rememberScrollState()
    BackHandler(onBack = onBack)
    Column(
        modifier
            .fillMaxSize()
            .background(SetteeColors.Background)
            .padding(horizontal = SetteeSpace.ScreenSide, vertical = SetteeSpace.ScreenTopBottom),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(title, style = SetteeType.ScreenTitle)
        Column(
            Modifier.weight(1f).widthIn(max = 900.dp).verticalScroll(scroll),
            verticalArrangement = Arrangement.spacedBy(lineGap),
            content = content,
        )
        ActionButton(
            stringResource(R.string.common_done),
            onClick = onBack,
            modifier = Modifier.dpadScrolls(scroll).startsFocused(),
        )
    }
}
