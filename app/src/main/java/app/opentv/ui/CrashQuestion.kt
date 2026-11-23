/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.res.stringResource
import app.opentv.R
import app.opentv.core.CrashReports
import app.opentv.ui.design.PanelBody
import app.opentv.ui.design.PanelRow
import app.opentv.ui.design.PanelTitle
import app.opentv.ui.design.SidePanel

/**
 * After a crash or freeze while Crash Reports were off, asks over the first screen whether to send
 * the report that was held (ADR 0009), when [crashReports] says to ask.
 *
 * The Highlight starts on Send, unlike Setup's "Not now": this is about the one report of a crash the
 * viewer just saw, not a standing yes. Back answers nothing, and the question comes back next launch.
 */
@Composable
fun CrashQuestion(crashReports: CrashReports) {
    val ask by crashReports.askAboutHeld.collectAsState()
    if (!ask) return
    SidePanel(onBack = crashReports::askLater) {
        PanelTitle(stringResource(R.string.crash_question_title))
        PanelBody(stringResource(R.string.crash_question_body))
        PanelRow(stringResource(R.string.crash_question_send), onClick = {
            crashReports.answer(CrashReports.Answer.SEND)
        })
        PanelRow(
            stringResource(R.string.crash_question_dont_send),
            onClick = { crashReports.answer(CrashReports.Answer.DONT_SEND) },
        )
        PanelRow(
            stringResource(R.string.crash_question_always_send),
            onClick = { crashReports.answer(CrashReports.Answer.ALWAYS_SEND) },
        )
    }
}
