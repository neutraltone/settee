/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv.core

/**
 * One of Settee's own services turning down Settee's request, as a Crash Report names it (ADR 0010):
 * which [service], what Settee was asking ([operation]) and how it failed ([status], `http-403` or
 * `unreadable-reply`). Nothing else, so nothing from the request or the reply can ride along. Built
 * only through [ofStatus] and [unreadable], which decide what counts.
 *
 * An exception, unlike the other [HandledReport]s, only for its stack trace, which is sent: it shows
 * the client that asked, the one way to tell IntroDB from TheIntroDB, which share [Service.INTRODB].
 * Never thrown.
 */
class ServiceFailure private constructor(val service: Service, val operation: Operation, val status: String) :
    Exception("${service.name} ${operation.tag} $status"),
    HandledReport {

    /** Settee's own services, each by the `service` [tag] its reports carry. */
    enum class Service(val tag: String) {
        OPENSUBTITLES("opensubtitles"),
        TMDB("tmdb"),

        /** IntroDB or TheIntroDB: the stack trace tells them apart, through the client that asked. */
        INTRODB("introdb"),

        /** GitHub's latest-release API, which the update check asks. */
        UPDATE_CHECK("update-check"),
    }

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
         * Only the statuses that point at Settee's key, request or parsing. 429 and 5xx are the
         * service's own trouble, and a 406 is OpenSubtitles' quota: none of them is Settee's bug.
         */
        private val REPORTED = setOf(400, 401, 403, 404, 410)
        private const val UNAUTHORISED = 401

        /**
         * [code] answered to [operation] at [service], or null when it isn't one to report. A 401 is
         * Settee's key refused, unless the request carried [viewersCredentials], a password or a
         * session token of the viewer's: then it's theirs, wrong or expired, and not Settee's bug.
         */
        fun ofStatus(service: Service, operation: Operation, code: Int, viewersCredentials: Boolean = false) =
            if (code in REPORTED && !(code == UNAUTHORISED && viewersCredentials)) {
                ServiceFailure(service, operation, "http-$code")
            } else {
                null
            }

        /** A reply to [operation] Settee couldn't read: a page in front of the API, or an API that changed shape. */
        fun unreadable(service: Service, operation: Operation) = ServiceFailure(service, operation, UNREADABLE)
    }
}
