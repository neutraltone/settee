/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv.ui.onboarding

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import app.opentv.ui.design.ScreenFrame
import app.opentv.ui.design.SetteeColors
import app.opentv.ui.design.SetteeType
import app.opentv.ui.design.Text
import app.opentv.ui.settings.QR_PANEL_HEIGHT
import app.opentv.ui.settings.QR_PANEL_WIDTH

/**
 * The phone pairing screen's layout: centred, [code] in a fixed space on the left, and on the right the
 * title, the help, [rows], [actions] and [attribution].
 */
@Composable
internal fun StepLayout(
    code: @Composable () -> Unit,
    rows: @Composable () -> Unit,
    actions: @Composable () -> Unit,
    title: String,
    help: String,
    attribution: @Composable () -> Unit,
) {
    ScreenFrame {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(48.dp)) {
                // Only the code counts in the row's height, as on the pairing screen, so the code sits
                // where that screen's did; the address to type hangs below it.
                Box(
                    Modifier
                        .size(QR_PANEL_WIDTH, QR_PANEL_WIDTH)
                        .wrapContentHeight(Alignment.Top, unbounded = true),
                ) {
                    Box(Modifier.size(QR_PANEL_WIDTH, QR_PANEL_HEIGHT)) { code() }
                }
                Column(Modifier.widthIn(max = WORDS_WIDTH)) {
                    Text(title, SetteeType.ScreenTitle)
                    Spacer(Modifier.height(12.dp))
                    Text(
                        help,
                        SetteeType.Synopsis,
                        color = SetteeColors.QuietText,
                    )
                    Spacer(Modifier.height(20.dp))
                    rows()
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        modifier = Modifier.padding(top = 4.dp).testTag(STEP_ACTIONS),
                    ) { actions() }
                    Spacer(Modifier.height(24.dp))
                    attribution()
                }
            }
        }
    }
}

/**
 * Takes the Highlight when it appears, asking a frame at a time, while this window has the system's
 * focus, until it has it or [FOCUS_PATIENCE_MILLIS] pass: asked once, a frame later, it can be refused
 * while the row that had the Highlight is still going.
 */
@Composable
internal fun Modifier.takesHighlight(): Modifier {
    val focus = remember { FocusRequester() }
    var highlighted by remember { mutableStateOf(false) }
    val windowFocused = LocalWindowInfo.current.isWindowFocused
    LaunchedEffect(focus, windowFocused) {
        if (!windowFocused) return@LaunchedEffect
        val until = System.currentTimeMillis() + FOCUS_PATIENCE_MILLIS
        while (!highlighted && System.currentTimeMillis() < until) {
            withFrameNanos {}
            runCatching { focus.requestFocus() }
        }
    }
    return focusRequester(focus).onFocusChanged { highlighted = it.hasFocus }
}

private const val FOCUS_PATIENCE_MILLIS = 2_000L

/** As wide as the pairing screen's words. */
private val WORDS_WIDTH = 520.dp

/** The step's own buttons, apart from a Try again for the phone's server. */
const val STEP_ACTIONS = "step_actions"
