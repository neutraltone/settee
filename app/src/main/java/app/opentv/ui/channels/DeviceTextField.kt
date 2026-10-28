/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv.ui.channels

import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.input.InputTransformation
import androidx.compose.foundation.text.input.TextFieldLineLimits
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.input.ImeAction

/**
 * A one-line text box that types with the device's own keyboard, so a Fire TV's mic dictation and
 * Gboard's voice input work in it.
 *
 * The keyboard waits for OK rather than opening when the highlight lands here: once open it takes
 * the D-pad, so opening on arrival would trap a viewer who was only passing through. It takes a
 * [TextFieldState] because Compose's String-valued text field ignores `showKeyboardOnFocus` and
 * opens the keyboard on arrival regardless.
 *
 * The keyboard's action key (Search, Done) runs [onSubmit]; without one it closes the keyboard and
 * moves the highlight down onto whatever the box filters. Compose's own handling does nothing for
 * Search, and a Fire TV keyboard covers the screen, so the viewer would be left with no way to see
 * the results but Back.
 */
@Composable
internal fun DeviceTextField(
    state: TextFieldState,
    placeholder: String,
    maxLength: Int,
    modifier: Modifier = Modifier,
    imeAction: ImeAction = ImeAction.Done,
    onSubmit: (() -> Unit)? = null,
) {
    val focusManager = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    OutlinedTextField(
        state = state,
        placeholder = { Text(placeholder) },
        lineLimits = TextFieldLineLimits.SingleLine,
        inputTransformation = cutTo(maxLength),
        keyboardOptions = KeyboardOptions(imeAction = imeAction, showKeyboardOnFocus = false),
        onKeyboardAction = {
            keyboard?.hide()
            onSubmit?.invoke() ?: focusManager.moveFocus(FocusDirection.Down)
        },
        modifier = modifier,
    )
}

/** The longest search either search box takes, the Search screen's or the Channel manager's. */
internal const val MAX_SEARCH_LENGTH = 40

/**
 * Keeps the first [maxLength] characters of whatever arrives. Compose's own `maxLength` rejects
 * the whole edit instead, which throws away a dictated phrase that runs one word over.
 */
private fun cutTo(maxLength: Int) = InputTransformation {
    if (length > maxLength) replace(maxLength, length, "")
}
