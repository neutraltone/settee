/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv.ui.design

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp

/**
 * Typing text for a setting, as a screen of its own: [title], one box holding [initial] with the
 * Highlight on it, and a Save below. OK on the box opens the device's keyboard, whose Done key
 * saves, as Save does. Back leaves without saving.
 *
 * Nothing blank is saved unless [allowBlank] (for an optional setting, where empty clears it),
 * nor anything [refusal] turns down; its reason shows under the box. [hint]
 * is a quiet line under that, such as where else the setting can be typed. Text longer than
 * [maxLength] is cut to fit, however it arrives.
 */
@Composable
fun EntryScreen(
    title: String,
    initial: String,
    saveLabel: String,
    onSave: (String) -> Unit,
    onCancel: () -> Unit,
    placeholder: String = "",
    hint: String? = null,
    keyboardType: KeyboardType = KeyboardType.Text,
    maxLength: Int? = null,
    allowBlank: Boolean = false,
    refusal: (String) -> String? = { null },
) {
    val text = rememberTextFieldState(initial)
    val box = remember { FocusRequester() }
    BackHandler(onBack = onCancel)
    LaunchedEffect(Unit) {
        // A frame first: until the input mode has gone to keyboard, nothing can take focus.
        withFrameNanos {}
        runCatching { box.requestFocus() }
    }
    val typed = text.text.toString()
    val refused = refusal(typed)
    // The text as it is when Save or Done is pressed, not as it was when last drawn: a Done straight
    // after typing comes before the screen has redrawn, and would save what was there before.
    val save = {
        val now = text.text.toString()
        if ((allowBlank || now.isNotBlank()) && refusal(now) == null) onSave(now)
    }
    Column(
        Modifier
            .fillMaxSize()
            .background(SetteeColors.Background)
            .padding(horizontal = SetteeSpace.ScreenSide, vertical = SetteeSpace.ScreenTopBottom),
    ) {
        Text(title, SetteeType.ScreenTitle)
        Spacer(Modifier.height(SetteeSpace.RowGap))
        Column(Modifier.widthIn(max = BOX_WIDTH).fillMaxWidth()) {
            TextField(
                state = text,
                placeholder = placeholder,
                maxLength = maxLength,
                keyboardType = keyboardType,
                onSubmit = save,
                modifier = Modifier.fillMaxWidth().focusRequester(box),
            )
            refused?.let {
                Spacer(Modifier.height(LINE_GAP))
                Text(it, SetteeType.Body, color = SetteeColors.Accent)
            }
            hint?.let {
                Spacer(Modifier.height(LINE_GAP))
                Text(it, SetteeType.Body, color = SetteeColors.QuietText)
            }
            Spacer(Modifier.height(SetteeSpace.RowGap))
            ActionButton(saveLabel, onClick = save)
        }
    }
}

private val BOX_WIDTH = 760.dp
private val LINE_GAP = 8.dp
