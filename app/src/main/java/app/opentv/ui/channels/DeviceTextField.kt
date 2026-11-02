/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv.ui.channels

import android.view.InputDevice
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.input.InputTransformation
import androidx.compose.foundation.text.input.TextFieldLineLimits
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
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
 * OK is handled here because Compose's own handling never opened a keyboard on a Fire TV: with
 * `showKeyboardOnFocus` off, a highlighted box has no input session, and its OK only asks for a
 * keyboard to be shown, with nothing to type into. Switching the option on when OK is pressed makes
 * Compose start the session and show the keyboard; it goes back off when the highlight leaves, so
 * arriving again still leaves the keyboard shut. That restart on a changed option is Compose's
 * behaviour rather than a promise (no public API starts a session from a key), so
 * SearchContentTest checks the input connection itself, to catch an upgrade that changes it.
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
    var chosen by remember { mutableStateOf(false) }
    OutlinedTextField(
        state = state,
        placeholder = { Text(placeholder) },
        lineLimits = TextFieldLineLimits.SingleLine,
        inputTransformation = cutTo(maxLength),
        keyboardOptions = KeyboardOptions(imeAction = imeAction, showKeyboardOnFocus = chosen),
        onKeyboardAction = {
            keyboard?.hide()
            onSubmit?.invoke() ?: focusManager.moveFocus(FocusDirection.Down)
        },
        modifier = modifier
            .onFocusChanged { if (!it.hasFocus) chosen = false }
            .onPreviewKeyEvent { event ->
                if (!event.isRemoteOk) return@onPreviewKeyEvent false
                if (event.type == KeyEventType.KeyDown) {
                    // Chosen already means a session is open, and Back has closed the keyboard over it.
                    if (chosen) keyboard?.show() else chosen = true
                }
                true
            },
    )
}

/**
 * Whether [this] is a remote's OK. Some TV-box remotes send Enter rather than D-pad centre, which
 * Compose would take as the keyboard's action key. On a real keyboard, or from the on-screen one,
 * Enter still is that.
 */
private val KeyEvent.isRemoteOk: Boolean
    get() = when (key) {
        Key.DirectionCenter -> true
        Key.Enter, Key.NumPadEnter ->
            nativeKeyEvent.flags and android.view.KeyEvent.FLAG_SOFT_KEYBOARD == 0 &&
                nativeKeyEvent.device?.keyboardType != InputDevice.KEYBOARD_TYPE_ALPHABETIC
        else -> false
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
