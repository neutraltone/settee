/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.State
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import app.opentv.R
import app.opentv.core.CrashReports
import app.opentv.core.ErrorReport
import app.opentv.core.ReportedSource
import app.opentv.core.ServiceLocator
import app.opentv.core.ViewerError
import app.opentv.ui.design.PanelBody
import app.opentv.ui.design.PanelRow
import app.opentv.ui.design.PanelTitle
import app.opentv.ui.design.PillButton
import app.opentv.ui.design.SidePanel

/**
 * An error that could be Settee's, as far as reporting it goes (ADR 0010). With Crash Reports on,
 * it's sent as it's shown, by [rememberErrorReport]; with them off, the viewer can send it from the
 * error's [ReportButton]. Either way its [detail] then says it went.
 */
@Stable
class ErrorReporting internal constructor(
    private val error: ViewerError,
    private val source: ReportedSource?,
    private val crashReports: CrashReports,
    private val reportsOn: State<Boolean>,
    private val sentWords: String,
) {
    private var sentHere by mutableStateOf(crashReports.hasSent(report(ErrorReport.SentBy.VIEWER)))

    /** Whether it's gone: with reports on it goes as it's shown, so it's said at once, not a frame later. */
    val sent: Boolean get() = sentHere || reportsOn.value

    /** Whether Report is offered: reports are off and the viewer hasn't sent it yet. */
    val offered: Boolean get() = !sent

    /** The error's detail line, with "Sent to Settee" after the code, status and format once it's gone. */
    val detail: String get() = if (sent) "${error.detail}$SEPARATOR$sentWords" else error.detail

    internal var asking by mutableStateOf(false)

    /** Whether [shown] is the error this reports, where a screen shows more than one. */
    internal fun isOf(shown: ViewerError) = shown == error

    private fun report(by: ErrorReport.SentBy) = ErrorReport(error.detail, source, by)

    /** Sent by Settee as it's shown, while reports are on: [CrashReports] sends each failure once. */
    internal fun sendAsShown() {
        if (reportsOn.value) crashReports.report(report(ErrorReport.SentBy.SETTEE))
    }

    /** The viewer's Send, or Always send, which turns reports on first: their choice in a panel that says so. */
    internal fun send(always: Boolean) {
        asking = false
        if (always) crashReports.setOn(true)
        crashReports.report(report(ErrorReport.SentBy.VIEWER))
        sentHere = true
    }

    private companion object {
        const val SEPARATOR = " · "
    }
}

/**
 * [error]'s detail line as it's drawn: saying it went, once this reports it and it has. For a screen
 * with more than one error, or an error that may not be reported at all.
 */
fun ErrorReporting?.detailOf(error: ViewerError): String = this?.takeIf { it.isOf(error) }?.detail ?: error.detail

/**
 * [error]'s reporting, or null where it's never reported: in a build without Settee's DSN, and on a
 * failure that's certainly the viewer's own ([ErrorReport.offeredFor]). With Crash Reports on, sends
 * it as it's shown. [source] is the kind of Source the failure came from, null for one that isn't a
 * Source's, such as an OpenSubtitles sign-in.
 */
@Composable
fun rememberErrorReport(error: ViewerError?, source: ReportedSource?): ErrorReporting? {
    val crashReports = ServiceLocator.get(LocalContext.current).crashReports
    val reportsOn = crashReports.on.collectAsState()
    val sentWords = stringResource(R.string.report_sent_to_settee)
    val reporting = remember(error, source, crashReports, sentWords) {
        error?.takeIf { crashReports.available && ErrorReport.offeredFor(it) }?.let {
            ErrorReporting(it, source, crashReports, reportsOn, sentWords)
        }
    }
    // Every time the error is composed: CrashReports, not this, makes sure it goes only once.
    LaunchedEffect(reporting, reportsOn.value) { reporting?.sendAsShown() }
    return reporting
}

/**
 * Report, the last button in [reporting]'s error's row while it's [offered][ErrorReporting.offered]:
 * OK asks in a Side Panel whether to send it. Once it's sent, Report goes, and the Highlight goes to
 * [returnTo], the error's main action (Try again, or its first button), rather than nowhere.
 */
@Composable
fun ReportButton(reporting: ErrorReporting?, returnTo: FocusRequester, modifier: Modifier = Modifier) {
    reporting ?: return
    var giveBack by remember(reporting) { mutableStateOf(false) }
    LaunchedEffect(giveBack) {
        if (!giveBack) return@LaunchedEffect
        // Once the panel's window has gone and focus is back in this one.
        withFrameNanos {}
        runCatching { returnTo.requestFocus() }
        giveBack = false
    }
    if (reporting.offered) {
        PillButton(stringResource(R.string.report_button), onClick = { reporting.asking = true }, modifier = modifier)
    }
    if (reporting.asking) {
        ReportQuestion(
            onSend = { always ->
                reporting.send(always)
                giveBack = true
            },
            onClose = { reporting.asking = false },
        )
    }
}

/**
 * Whether to send this one error, modelled on the after-crash [CrashQuestion]: the Highlight starts
 * on Send, and Back, like Don't send, closes it with nothing sent.
 */
@Composable
private fun ReportQuestion(onSend: (always: Boolean) -> Unit, onClose: () -> Unit) {
    SidePanel(onBack = onClose) {
        PanelTitle(stringResource(R.string.report_question_title))
        PanelBody(stringResource(R.string.report_question_body))
        PanelBody(stringResource(R.string.report_question_always_note))
        PanelRow(stringResource(R.string.report_question_send), onClick = { onSend(false) })
        PanelRow(stringResource(R.string.report_question_dont_send), onClick = onClose)
        PanelRow(stringResource(R.string.report_question_always_send), onClick = { onSend(true) })
    }
}
