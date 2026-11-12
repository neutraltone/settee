/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv

import android.view.KeyEvent
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.ComposeContentTestRule
import androidx.compose.ui.unit.dp
import app.opentv.ui.design.SidePanelWidth
import com.google.common.truth.Truth.assertThat
import org.robolectric.shadows.ShadowDialog

/** Asserts this node is in a Side Panel: in the panel's width down the right of a [TV_1080P] screen. */
fun SemanticsNodeInteraction.assertIsInSidePanel(): SemanticsNodeInteraction = apply {
    assertThat(getUnclippedBoundsInRoot().left.value).isAtLeast((TV_WIDTH - SidePanelWidth).value)
}

/** Presses Back on the window on top, which is the open Side Panel's. */
fun ComposeContentTestRule.pressBackOnPanel() {
    // A panel opened by a press is only composed once things settle; until then there's no window.
    waitForIdle()
    val panel = checkNotNull(ShadowDialog.getLatestDialog()) { "No Side Panel is open" }
    runOnUiThread {
        panel.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_BACK))
        panel.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_BACK))
    }
    waitForIdle()
}

private val TV_WIDTH = 960.dp
