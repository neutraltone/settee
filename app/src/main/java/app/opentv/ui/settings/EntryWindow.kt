/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv.ui.settings

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import app.opentv.R
import app.opentv.ui.design.EntryScreen

/**
 * An [EntryScreen] opened from a Settings row. It's a window of its own over Settings rather than a
 * route: a route would carry the text (a guide address can hold a login) in the back stack, and
 * Settings would be rebuilt on the way back, losing which row had the Highlight. Over a window, the
 * row keeps it, so Back and Save both land on it again.
 */
@Composable
internal fun EntryWindow(
    title: String,
    initial: String,
    onSave: (String) -> Unit,
    onCancel: () -> Unit,
    keyboardType: KeyboardType = KeyboardType.Text,
    hint: String? = null,
    maxLength: Int? = null,
    allowBlank: Boolean = false,
    refusal: (String) -> String? = { null },
) {
    Dialog(
        onDismissRequest = onCancel,
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false),
    ) {
        // The entry screen covers everything with its own background, so a dim behind it is a
        // second full-screen layer for nothing.
        val window = (LocalView.current.parent as? DialogWindowProvider)?.window
        LaunchedEffect(window) { window?.setDimAmount(0f) }
        EntryScreen(
            title = title,
            initial = initial,
            saveLabel = stringResource(R.string.common_save),
            onSave = onSave,
            onCancel = onCancel,
            hint = hint,
            keyboardType = keyboardType,
            maxLength = maxLength,
            allowBlank = allowBlank,
            refusal = refusal,
        )
    }
}
