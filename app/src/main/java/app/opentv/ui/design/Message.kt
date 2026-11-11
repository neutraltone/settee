/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv.ui.design

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp

/**
 * What a screen says in its middle when it has nothing else to show: an optional [title], the
 * [body] in quiet text, and [below] for what can be done about it, usually an [ActionButton].
 */
@Composable
fun Message(body: String, modifier: Modifier = Modifier, title: String? = null, below: @Composable () -> Unit = {}) {
    Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.widthIn(max = MESSAGE_WIDTH)) {
            title?.let {
                Text(it, SetteeType.Section, textAlign = TextAlign.Center)
                Spacer(Modifier.height(MESSAGE_GAP))
            }
            Text(body, SetteeType.Body, color = SetteeColors.QuietText, textAlign = TextAlign.Center)
            Spacer(Modifier.height(MESSAGE_GAP * 2))
            below()
        }
    }
}

/** A button with words on it, such as a message's way forward or a dialog's Done. */
@Composable
fun ActionButton(label: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    HighlightSurface(onClick = onClick, style = HighlightStyle.Button, modifier = modifier) { highlighted ->
        Text(
            label,
            SetteeType.Body,
            color = if (highlighted) SetteeColors.OnAccent else SetteeColors.Text,
            modifier = Modifier.padding(horizontal = BUTTON_PADDING_SIDE, vertical = BUTTON_PADDING_TOP),
        )
    }
}

private val MESSAGE_WIDTH = 460.dp
private val MESSAGE_GAP = 8.dp
private val BUTTON_PADDING_SIDE = 24.dp
private val BUTTON_PADDING_TOP = 10.dp
