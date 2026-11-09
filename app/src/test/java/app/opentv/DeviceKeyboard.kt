/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv

import android.content.Context
import android.os.SystemClock
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.ExtractedTextRequest
import android.view.inputmethod.InputConnection
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import androidx.compose.ui.test.junit4.ComposeContentTestRule
import androidx.test.core.app.ApplicationProvider
import org.robolectric.Shadows.shadowOf

/**
 * Drives a text box the way the device's keyboard does: through the box's own input connection,
 * which is all a Fire TV keyboard has to go on. Compose's text-field test helpers can't reach a
 * box drawn by an Android EditText.
 */
internal class DeviceKeyboard(private val compose: ComposeContentTestRule, private val root: () -> View) {

    /** The [index]th text box on screen, in layout order. */
    fun box(index: Int = 0): EditText = compose.runOnIdle { root().editTexts()[index] }

    /** Whether [box] has the Highlight. */
    fun highlighted(box: EditText = box()): Boolean = compose.runOnIdle { box.isFocused }

    /** The keyboard's connection to [box], as it gets one when it opens over it. */
    fun connection(box: EditText = box()): Pair<EditorInfo, InputConnection> = compose.runOnIdle {
        val info = EditorInfo()
        info to checkNotNull(box.onCreateInputConnection(info)) { "the box gave the keyboard nothing to type into" }
    }

    /** Types [text] into [box] as the keyboard does, dictation included. */
    fun type(text: String, box: EditText = box()) {
        val (_, input) = connection(box)
        compose.runOnIdle { input.commitText(text, 1) }
        compose.waitForIdle()
    }

    /** Replaces everything in [box] with [text], as picking a suggestion over the whole word does. */
    fun replaceAll(text: String, box: EditText = box()) {
        val (_, input) = connection(box)
        compose.runOnIdle {
            input.setSelection(0, box.text.length)
            input.commitText(text, 1)
        }
        compose.waitForIdle()
    }

    /** Presses the keyboard's action key (Search, Done) on [box]. */
    fun pressAction(box: EditText = box()) {
        val (info, input) = connection(box)
        compose.runOnIdle { input.performEditorAction(info.imeOptions and EditorInfo.IME_MASK_ACTION) }
        compose.waitForIdle()
    }

    /** What the keyboard reads as [box]'s text: the input it shows above its keys. */
    fun textShownBy(box: EditText = box()): String {
        val (_, input) = connection(box)
        return compose.runOnIdle { input.getExtractedText(ExtractedTextRequest(), 0).text.toString() }
    }

    val showing: Boolean
        get() {
            compose.waitForIdle()
            return shadowOf(manager()).isSoftInputVisible
        }

    /** Presses [keyCode] on the remote, through the window as a real key arrives. */
    fun remote(keyCode: Int) {
        compose.runOnIdle {
            root().dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, keyCode))
            root().dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_UP, keyCode))
        }
        compose.waitForIdle()
    }

    /** Taps the middle of [box], through the window as a finger's touch arrives. */
    fun tap(box: EditText = box()) {
        compose.runOnIdle {
            val boxAt = IntArray(2).also(box::getLocationInWindow)
            val rootAt = IntArray(2).also(root()::getLocationInWindow)
            val x = (boxAt[0] - rootAt[0] + box.width / 2).toFloat()
            val y = (boxAt[1] - rootAt[1] + box.height / 2).toFloat()
            val now = SystemClock.uptimeMillis()
            for (action in listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP)) {
                val touch = MotionEvent.obtain(now, now, action, x, y, 0)
                root().dispatchTouchEvent(touch)
                touch.recycle()
            }
        }
        compose.waitForIdle()
    }

    /** Back on a remote while the keyboard is open: it closes the keyboard and nothing else. */
    fun close() {
        compose.runOnIdle { manager().hideSoftInputFromWindow(root().windowToken, 0) }
        compose.waitForIdle()
    }

    private fun manager() =
        ApplicationProvider.getApplicationContext<Context>().getSystemService(InputMethodManager::class.java)
}

private fun View.editTexts(): List<EditText> = when (this) {
    is EditText -> listOf(this)
    is ViewGroup -> (0 until childCount).flatMap { getChildAt(it).editTexts() }
    else -> emptyList()
}
