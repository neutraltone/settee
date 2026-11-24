/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import app.opentv.core.CrashReports
import app.opentv.core.ErrorReport
import app.opentv.core.JourneyTimer
import app.opentv.data.model.SourceKind
import app.opentv.core.ServiceLocator
import app.opentv.core.ViewerError
import app.opentv.ui.design.SendReport
import app.opentv.data.repo.MediaKey

/**
 * Send report for [error], for [ErrorMessage][app.opentv.ui.design.ErrorMessage]'s `sendReport`, or
 * null where it isn't offered (ADR 0010): in a build without Settee's DSN, and on a failure that's
 * certainly the viewer's own ([ErrorReport.offeredFor]). Offered whatever the viewer's crash report
 * choice, which pressing it doesn't change. [source] is the kind of Source the failure came from,
 * null for one that isn't a Source's, such as an OpenSubtitles sign-in.
 */
@Composable
fun sendReportFor(error: ViewerError?, source: String?): (() -> Unit)? {
    val crashReports = ServiceLocator.get(LocalContext.current).crashReports
    return remember(error, source, crashReports) {
        error?.takeIf { crashReports.available && ErrorReport.offeredFor(it) }?.let { shown ->
            { crashReports.report(CrashReports.Handled.VIEWER_REPORT, ErrorReport(shown.detail, source)) }
        }
    }
}

/**
 * Send report for [failure] on its own, after a form's or a list's buttons rather than inside the
 * error, so it's Down from them and they never move. Nothing where [sendReportFor] offers nothing.
 */
@Composable
fun SendReportAfter(failure: ViewerError?, source: String?) {
    val send = sendReportFor(failure, source) ?: return
    failure?.let { SendReport(it.sentence, it.detail, send) }
}

/** The kind of provider what a player shows comes from, for Send report on its failure: set by the player. */
val LocalReportedSource = compositionLocalOf<String?> { null }

/**
 * The kind of Source [key] plays from, for an Error Report: a film or episode is an Xtream panel's
 * (the only kind with them), an add-on's stream Stremio's, a channel or its catch-up its provider's,
 * and a recording, kept on the device or the viewer's NAS, none.
 */
suspend fun ServiceLocator.Graph.reportedSourceOf(key: MediaKey): String? = when (key) {
    is MediaKey.Movie, is MediaKey.Episode -> JourneyTimer.sourceOf(SourceKind.XTREAM)
    is MediaKey.AddonStream -> JourneyTimer.STREMIO
    is MediaKey.Live -> channelSourceOf(key.channelId)
    is MediaKey.Catchup -> channelSourceOf(key.channelId)
    is MediaKey.Recording -> null
}

private suspend fun ServiceLocator.Graph.channelSourceOf(channelId: Long): String? =
    database.channels().byId(channelId)?.let { database.sources().byId(it.sourceId) }?.kind.let(JourneyTimer::sourceOf)
