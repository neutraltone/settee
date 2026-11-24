/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import app.opentv.R
import app.opentv.core.ErrorReport
import app.opentv.core.ReportedSource
import app.opentv.core.ServiceLocator
import app.opentv.core.ViewerError
import app.opentv.ui.design.OfferedReport
import app.opentv.ui.design.ReportOffer

/**
 * Send report for [error], for [ErrorMessage][app.opentv.ui.design.ErrorMessage]'s `sendReport`, or
 * null where it isn't offered (ADR 0010): in a build without Settee's DSN, and on a failure that's
 * certainly the viewer's own ([ErrorReport.offeredFor]). Offered whatever the viewer's crash report
 * choice, which pressing it doesn't change. [source] is the kind of Source the failure came from,
 * null for one that isn't a Source's, such as an OpenSubtitles sign-in.
 */
@Composable
fun sendReportFor(error: ViewerError?, source: ReportedSource?): ReportOffer? {
    val crashReports = ServiceLocator.get(LocalContext.current).crashReports
    val note = stringResource(R.string.report_note)
    val send = stringResource(R.string.report_send)
    val sent = stringResource(R.string.report_sent)
    return remember(error, source, crashReports, note, send, sent) {
        error?.takeIf { crashReports.available && ErrorReport.offeredFor(it) }?.let { shown ->
            ReportOffer(note, send, sent) { crashReports.report(ErrorReport(shown.detail, source, ErrorReport.SentBy.VIEWER)) }
        }
    }
}

/**
 * Send report for [failure] on its own, after a form's or a list's buttons rather than inside the
 * error, so it's Down from them and they never move. Nothing where [sendReportFor] offers nothing.
 */
@Composable
fun SendReportAfter(failure: ViewerError?, source: ReportedSource?) {
    sendReportFor(failure, source)?.let { OfferedReport(it) }
}
