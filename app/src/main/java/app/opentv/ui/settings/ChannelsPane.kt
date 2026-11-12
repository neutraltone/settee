/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv.ui.settings

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import app.opentv.R
import app.opentv.ui.design.ListRow

/** Channels: the channel manager is bigger than a row, so it opens as its own screen. */
@Composable
internal fun ChannelsPane(onOpenChannels: () -> Unit) {
    val row = screenRow("channels", onOpenChannels)
    ListRow(stringResource(R.string.settings_row_channels), onClick = row.open, modifier = row.modifier, opens = true)
}
