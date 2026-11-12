/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv.ui

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import app.opentv.R
import app.opentv.core.batteryOptimizationRequests
import app.opentv.core.isIgnoringBatteryOptimizations
import app.opentv.ui.design.PanelBody
import app.opentv.ui.design.PanelRow
import app.opentv.ui.design.PanelTitle
import app.opentv.ui.design.SidePanel

/**
 * A once-per-session nudge shown the first time the user records or schedules while OpenTV still
 * isn't exempt from battery optimisation. It doesn't block the recording — that has already
 * started — it just offers to grant the exemption so the capture survives standby, the way a
 * set-top box does. Once granted (or once shown this session) it stays out of the way.
 */
object RecordingBackgroundPrompt {

    @Volatile private var shownThisSession = false

    /**
     * True at most once per app session, and only while OpenTV still isn't Doze-exempt. Never on a
     * device with no screen to grant it on, a Fire TV Stick for one: the dialog would come back every
     * session to offer something the viewer can't do.
     */
    fun shouldShow(context: Context): Boolean = !shownThisSession &&
        !context.isIgnoringBatteryOptimizations() &&
        batteryOptimizationRequests(context.packageManager, context.packageName).isNotEmpty()

    fun markShown() {
        shownThisSession = true
    }
}

/**
 * The nudge itself, in a Side Panel. [onAllow] should fire the system battery-optimisation request;
 * [onDismiss] is "Not now", and Back. The Highlight starts on Allow so one OK press grants it.
 */
@Composable
fun RecordingBackgroundDialog(onAllow: () -> Unit, onDismiss: () -> Unit) {
    SidePanel(onBack = onDismiss) {
        PanelTitle(stringResource(R.string.rec_background_prompt_title))
        PanelBody(stringResource(R.string.rec_background_prompt_body))
        PanelRow(stringResource(R.string.rec_background_prompt_allow), onClick = onAllow)
        PanelRow(stringResource(R.string.rec_background_prompt_not_now), onClick = onDismiss)
    }
}
