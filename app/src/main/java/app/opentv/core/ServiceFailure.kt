/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv.core

/**
 * One of Settee's own services (OpenSubtitles, TMDB, IntroDB, the update check) turning down Settee's
 * request, as a Crash Report names it (ADR 0010): what Settee was asking ([operation]) and how it
 * failed ([status], `http-403` or `unreadable-reply`). Nothing else, so nothing from the request or
 * the reply can ride along. Built only through [ofStatus] and [unreadable], which decide what counts.
 */
class ServiceFailure private constructor(val operation: Operation, val status: String) {

    /** What Settee was asking the service: a fixed list, so the tag can only ever be one of these. */
    enum class Operation(val tag: String) {
        SIGN_IN("sign-in"),

        /** OpenSubtitles: the signed-in account's downloads left. */
        ACCOUNT("account"),
        SEARCH("search"),
        DOWNLOAD("download"),
        DETAILS("details"),
        TIMINGS("timings"),
        LATEST_RELEASE("latest-release"),
    }

    companion object {
        const val UNREADABLE = "unreadable-reply"

        /**
         * Only the statuses that point at Settee's key, request or parsing. A 401 is a viewer's
         * password or token, 429 and 5xx are the service's own trouble, and a 406 is OpenSubtitles'
         * quota: none of them is Settee's bug.
         */
        private val REPORTED = setOf(400, 403, 404, 410)

        /** [code] answered to [operation], or null when it isn't one to report. */
        fun ofStatus(operation: Operation, code: Int): ServiceFailure? =
            if (code in REPORTED) ServiceFailure(operation, "http-$code") else null

        /** A reply to [operation] Settee couldn't read: a page in front of the API, or an API that changed shape. */
        fun unreadable(operation: Operation) = ServiceFailure(operation, UNREADABLE)
    }
}
