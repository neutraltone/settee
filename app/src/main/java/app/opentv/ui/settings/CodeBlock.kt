/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv.ui.settings

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.opentv.pairing.QrCodes
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

/** A QR code for [url], dark on white so a phone can read it off the TV, [size] square. */
@Composable
internal fun PhoneQrCode(url: String, description: String, size: Dp = QR_SIZE) {
    // Drawn once per address: encoding isn't free, and the content doesn't change.
    val qr = remember(url) { QrCodes.render(url, QR_SIZE_PX) } ?: return
    Image(
        qr.asImageBitmap(),
        contentDescription = description,
        modifier = Modifier
            .size(size)
            .background(Color.White, SetteeShapes.Card)
            .padding(QR_PADDING),
    )
}

private const val QR_SIZE_PX = 600
private val QR_SIZE = 200.dp
private val QR_PADDING = 10.dp
