/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv.ui.design

import android.content.Context
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.text.InputFilter
import android.text.InputType
import android.util.TypedValue
import android.view.InputDevice
import android.view.MotionEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.text.input.setTextAndPlaceCursorAtEnd
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusManager
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.res.ResourcesCompat
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat
import androidx.core.widget.doAfterTextChanged
import app.opentv.R

/**
 * A one-line text box that types with the device's own keyboard, so a Fire TV's mic dictation and
 * Gboard's voice input work in it, where the device offers them.
 *
 * It is an Android EditText rather than a Compose text field. Compose's TextFieldState field never
 * tells the keyboard when the text changes, so a Fire TV keyboard went on showing what it typed
 * after Settee cleared the box or filled in a Recent Search. Compose's String field does tell it,
 * but ignores `showKeyboardOnFocus` and opens the keyboard as soon as the highlight lands, which
 * traps a viewer who was only passing through, since an open keyboard takes the D-pad.
 *
 * So the keyboard waits for OK: the box never opens it on focus, and OK opens it here.
 */
@Composable
fun TextField(
    state: TextFieldState,
    placeholder: String,
    maxLength: Int,
    modifier: Modifier = Modifier,
    imeAction: ImeAction = ImeAction.Done,
    onSubmit: (() -> Unit)? = null,
) = TextBox(
    state = state,
    look = BoxLook(placeholder = placeholder),
    maxLength = maxLength,
    modifier = modifier,
    imeAction = imeAction,
    onSubmit = onSubmit,
)

/**
 * The same box for a screen that holds its text as a String, as the forms do. [onValueChange] may
 * change what it's given (the TMDB key trims it), and the box and keyboard then show the change.
 */
@Composable
fun TextField(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    label: String? = null,
    placeholder: String? = null,
    supportingText: String? = null,
    trailingIcon: (@Composable () -> Unit)? = null,
    keyboardType: KeyboardType = KeyboardType.Text,
    revealed: Boolean = false,
    imeAction: ImeAction = ImeAction.Done,
    onSubmit: (() -> Unit)? = null,
) {
    val state = remember { TextFieldState(value) }
    val latestValue by rememberUpdatedState(value)
    val latestOnChange by rememberUpdatedState(onValueChange)
    // The last edit handed to the screen. Being state, it recomposes this box in the same frame as
    // the screen's own change, so the box sees the screen's answer: kept, changed (the TMDB key is
    // trimmed) or turned down (a PIN keeps four digits), when the value wouldn't change at all.
    var handedOver by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(value) {
        if (state.text.toString() != value) state.setTextAndPlaceCursorAtEnd(value)
    }
    LaunchedEffect(state) {
        snapshotFlow { state.text.toString() }.collect { typed ->
            if (typed == latestValue) return@collect
            handedOver = typed
            latestOnChange(typed)
        }
    }
    if (handedOver != null) {
        SideEffect {
            handedOver = null
            if (state.text.toString() != value) state.setTextAndPlaceCursorAtEnd(value)
        }
    }
    TextBox(
        state = state,
        look = BoxLook(
            label = label,
            placeholder = placeholder,
            supportingText = supportingText,
            trailingIcon = trailingIcon,
            inputType = keyboardType.toInputType(revealed),
        ),
        maxLength = null,
        modifier = modifier,
        imeAction = imeAction,
        onSubmit = onSubmit,
    )
}

/** What's drawn around the text: a label above, a placeholder inside and a note below. */
private class BoxLook(
    val label: String? = null,
    val placeholder: String? = null,
    val supportingText: String? = null,
    val trailingIcon: (@Composable () -> Unit)? = null,
    val inputType: Int = InputType.TYPE_CLASS_TEXT,
)

/**
 * Password kinds also hide what's typed, and tell the keyboard not to learn it. A [revealed]
 * password shows, still without being learnt.
 */
private fun KeyboardType.toInputType(revealed: Boolean = false): Int = when (this) {
    KeyboardType.Password if revealed -> InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
    KeyboardType.Password -> InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
    KeyboardType.NumberPassword -> InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD
    KeyboardType.Number -> InputType.TYPE_CLASS_NUMBER
    KeyboardType.Uri -> InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
    else -> InputType.TYPE_CLASS_TEXT
}

@Composable
private fun TextBox(
    state: TextFieldState,
    look: BoxLook,
    maxLength: Int?,
    modifier: Modifier,
    imeAction: ImeAction,
    onSubmit: (() -> Unit)?,
) {
    val holder = remember { BoxHolder() }
    val focusManager = LocalFocusManager.current
    val submit by rememberUpdatedState(onSubmit)
    var highlighted by remember { mutableStateOf(false) }
    val settings = BoxSettings(
        inputType = look.inputType,
        imeOptions = imeAction.toEditorAction(),
        maxLength = maxLength,
        textColor = SetteeColors.Text.toArgb(),
        textSize = SetteeType.Body.fontSize.value,
        spokenLabel = look.label ?: look.placeholder,
    )
    val textHighlighted = { holder.box?.isFocused == true }
    Column(modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        look.label?.let { Text(it, SetteeType.Body, color = SetteeColors.QuietText) }
        Row(
            Modifier
                .fillMaxWidth()
                .background(SetteeColors.Surface, SetteeShapes.Card)
                // The Highlight is a thick coral edge, as a card's ring is: Material's outline only
                // thickened by a hair, which couldn't be seen from the sofa.
                .border(
                    width = if (highlighted) 3.dp else 1.dp,
                    color = if (highlighted) SetteeColors.Accent else SetteeColors.Rule,
                    shape = SetteeShapes.Card,
                )
                .padding(horizontal = 16.dp, vertical = 4.dp)
                .remoteKeys(focusManager, textHighlighted) { holder.box?.openKeyboard() },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.weight(1f).padding(vertical = 10.dp), contentAlignment = Alignment.CenterStart) {
                if (state.text.isEmpty() && look.placeholder != null) {
                    Text(look.placeholder, SetteeType.Body, color = SetteeColors.QuietText, maxLines = 1)
                }
                AndroidView(
                    factory = { context ->
                        DeviceEditText(context).apply {
                            holder.box = this
                            setSingleLine()
                            background = null
                            setPadding(0, 0, 0, 0)
                            typeface = ResourcesCompat.getFont(context, R.font.alegreya_sans_medium)
                            // Android's own cursor is the theme's teal. Older versions keep it:
                            // there's no way to set a cursor's colour before Android 10.
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                                textCursorDrawable = GradientDrawable().apply {
                                    setColor(SetteeColors.Accent.toArgb())
                                    setSize((2 * resources.displayMetrics.density).toInt(), 0)
                                }
                            }
                            showSoftInputOnFocus = false
                            setOnFocusChangeListener { box, has ->
                                // Compose sometimes asks its root view for focus while a layout is
                                // pending, and Android's layout then "restores" the root's default
                                // focus. With none set, that fails and Android clears the focus
                                // from this box. Being the default while highlighted makes the
                                // restore land back here.
                                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) box.isFocusedByDefault = has
                                highlighted = has
                            }
                            doAfterTextChanged { copyTo(state) }
                            // The cursor too: the Channel manager sends Left to the categories only
                            // from the start of the box.
                            onSelectionChange = { copyTo(state) }
                            // Without this a Fire TV keyboard, which covers the screen, would leave
                            // the viewer no way to see the results but Back.
                            setOnEditorActionListener { _, _, _ ->
                                closeKeyboard()
                                submit?.invoke() ?: focusManager.moveFocus(FocusDirection.Down)
                                true
                            }
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                    // Runs straight after the factory as well, so this is the one place the box
                    // takes its settings and text, and a change to either (a revealed password)
                    // reaches it.
                    update = { box ->
                        box.apply(settings)
                        box.show(state)
                    },
                )
            }
            look.trailingIcon?.invoke()
        }
        look.supportingText?.let { Text(it, SetteeType.Body, color = SetteeColors.QuietText) }
    }
}

/** How the EditText is set up, applied whenever any of it changes. */
private data class BoxSettings(
    val inputType: Int,
    val imeOptions: Int,
    val maxLength: Int?,
    val textColor: Int,
    val textSize: Float,
    val spokenLabel: String?,
)

/**
 * Up and Down leave the box and OK opens the keyboard. An open keyboard takes the D-pad itself, so
 * these only arrive while it's shut. Only while the text has the highlight: a Paste or reveal
 * button inside the box takes its own OK.
 */
private fun Modifier.remoteKeys(focusManager: FocusManager, textHighlighted: () -> Boolean, openKeyboard: () -> Unit) =
    onPreviewKeyEvent { event ->
        if (!textHighlighted()) return@onPreviewKeyEvent false
        val leaving = when (event.key) {
            Key.DirectionUp -> FocusDirection.Up
            Key.DirectionDown -> FocusDirection.Down
            else -> null
        }
        val down = event.type == KeyEventType.KeyDown
        when {
            // A single-line EditText would take Up to move its cursor to the start first.
            leaving != null -> if (down) focusManager.moveFocus(leaving)
            event.isRemoteOk -> if (down) openKeyboard()
            else -> return@onPreviewKeyEvent false
        }
        true
    }

private class BoxHolder {
    var box: DeviceEditText? = null
}

/**
 * An EditText that reports cursor moves, which Android only tells a subclass about, and tells a
 * screen reader its label, which Compose draws beside it where TalkBack can't see it.
 */
private class DeviceEditText(context: Context) : EditText(context) {
    // Null until construction is done: EditText reports a selection while it's still being built.
    var onSelectionChange: (() -> Unit)? = null
    var spokenLabel: String? = null
    var applied: BoxSettings? = null

    override fun onSelectionChanged(selStart: Int, selEnd: Int) {
        super.onSelectionChanged(selStart, selEnd)
        onSelectionChange?.invoke()
    }

    // A phone or tablet has no OK to press, so a tap opens the keyboard. Android's first tap on an
    // unfocused box only gives it focus and never counts as a click, so the tap clicks here.
    override fun onTouchEvent(event: MotionEvent): Boolean {
        val handled = super.onTouchEvent(event)
        if (event.actionMasked == MotionEvent.ACTION_UP && isFocused) performClick()
        return handled
    }

    // A tap, or a screen reader's double tap, which arrives as a click.
    override fun performClick(): Boolean {
        openKeyboard()
        return super.performClick()
    }

    override fun onInitializeAccessibilityNodeInfo(info: AccessibilityNodeInfo) {
        super.onInitializeAccessibilityNodeInfo(info)
        AccessibilityNodeInfoCompat.wrap(info).hintText = spokenLabel
    }

    fun apply(settings: BoxSettings) {
        if (settings == applied) return
        if (inputType != settings.inputType) {
            // After setSingleLine, which would otherwise undo a password's masking.
            inputType = settings.inputType
            setSelection(text.length)
        }
        imeOptions = settings.imeOptions
        // Cuts what arrives to the space left, rather than rejecting the whole edit, so a dictated
        // phrase that runs one word over isn't thrown away.
        filters = settings.maxLength?.let { arrayOf(InputFilter.LengthFilter(it)) } ?: emptyArray()
        setTextColor(settings.textColor)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, settings.textSize)
        spokenLabel = settings.spokenLabel
        applied = settings
    }

    /** Brings the box's text in step with what the screen holds. */
    fun show(state: TextFieldState) {
        val wanted = state.text.toString()
        if (text.toString() != wanted) {
            setText(wanted)
            setSelection(text.length)
        }
    }

    fun copyTo(state: TextFieldState) {
        val typed = text.toString()
        val cursor = TextRange(selectionStart.coerceAtLeast(0), selectionEnd.coerceAtLeast(0))
        if (state.text.toString() == typed && state.selection == cursor) return
        state.edit {
            if (toString() != typed) replace(0, length, typed)
            selection = cursor
        }
    }

    fun openKeyboard() {
        requestFocus()
        context.getSystemService(InputMethodManager::class.java)?.showSoftInput(this, 0)
    }

    fun closeKeyboard() {
        context.getSystemService(InputMethodManager::class.java)?.hideSoftInputFromWindow(windowToken, 0)
    }
}

private fun ImeAction.toEditorAction(): Int = when (this) {
    ImeAction.Search -> EditorInfo.IME_ACTION_SEARCH
    ImeAction.Next -> EditorInfo.IME_ACTION_NEXT
    ImeAction.Go -> EditorInfo.IME_ACTION_GO
    ImeAction.Send -> EditorInfo.IME_ACTION_SEND
    ImeAction.Previous -> EditorInfo.IME_ACTION_PREVIOUS
    else -> EditorInfo.IME_ACTION_DONE
}

/**
 * Whether [this] is a remote's OK. Some TV-box remotes send Enter rather than D-pad centre, which
 * the box would take as the keyboard's action key. On a real keyboard, or from the on-screen one,
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
