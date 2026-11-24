/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv.core

/**
 * An Error Report (ADR 0010): one failure the viewer pressed Send report on, as they saw it. Only
 * its [detail] line (code · status · format), which CrashReports scrubs before it goes, never the
 * sentence; its [kind]; the [source]'s kind, as [JourneyTimer.sourceOf] names it (or
 * [JourneyTimer.STREMIO]); and the [cause]'s stack trace where there is one.
 *
 * The kind is the detail line's first code: the [ErrorWording] or [app.opentv.player.PlaybackErrors]
 * category the sentence was chosen by (a provider's problem, an exception's type, Media3's error
 * code, or an HTTP status), and the one part of a failure that's the same in every language and
 * survives being saved, as a Source's last refresh failure is.
 *
 * An exception only so it goes through [CrashReports.report] as a [CrashReports.Handled.VIEWER_REPORT]:
 * its own message and stack trace are never sent, only the cause's classes and stack trace.
 */
class ErrorReport(val detail: String, val source: String?, cause: Throwable? = null) :
    Exception(MESSAGE, cause) {
    val kind: String = detail.substringBefore(SEPARATOR).trim().ifBlank { UNKNOWN }

    companion object {
        private const val MESSAGE = "Error Report"
        private const val SEPARATOR = " · "
        private const val UNKNOWN = "Unknown"

        /**
         * Codes of failures that are certainly the viewer's own, which never offer Send report: no
         * internet or an address that can't be found, and a login or address the viewer gave that
         * was turned down. A 403 isn't here: a provider or service can refuse Settee itself with one.
         */
        private val VIEWERS_OWN = setOf(
            "UnknownHost",
            "Connect",
            "NoRouteToHost",
            "LoginRejected",
            "AccountInactive",
            "BadAddress",
            "SignInRejected",
            "HTTP 401",
        )

        /** Whether [error] offers Send report: it has a detail line, and isn't certainly the viewer's own. */
        fun offeredFor(error: ViewerError): Boolean =
            error.detail.isNotBlank() && error.detail.split(SEPARATOR).none { it.trim() in VIEWERS_OWN }
    }
}
