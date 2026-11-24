/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv.core

/**
 * What [CrashReports.report] sends (ADRs 0009 and 0010): failures Settee's own code, keys or requests
 * can cause, a journey that was clearly too slow, and an error on screen that could be Settee's. Sealed,
 * so the list is closed on purpose: a Provider's failures (HTTP errors, dead streams, web pages
 * instead of video) and playback errors leave the device only as an [ErrorReport] of what the viewer
 * saw. Each kind carries only what its event needs, and its type alone decides how it's
 * sent and what it's sent as, so nothing pairs a kind with the wrong payload.
 *
 * - [CaughtError]: an error Settee caught rather than crashing (ADR 0009).
 * - [ServiceFailure]: one of Settee's own services turning down Settee's request.
 * - [JourneyTimer.SlowJourney]: Settee's own part of a journey clearly too slow, sent with no error at all.
 * - [ErrorReport]: a failure on screen, sent by Settee with Crash Reports on, or by the viewer's choice.
 */
sealed interface HandledReport

/**
 * An [error] of [kind] that Settee caught rather than crashing (ADR 0009), sent with its classes and
 * stack traces, and its messages only where [Kind.keepsMessages] says they're safe.
 */
class CaughtError(val kind: Kind, val error: Throwable) : HandledReport {
    /** The errors Settee catches and reports: only ones its own code can cause. */
    enum class Kind(internal val keepsMessages: Boolean) {
        /** A panel's reply that starts as JSON but Settee couldn't read. Its message quotes the reply, login too. */
        XTREAM_PARSE(keepsMessages = false),

        /**
         * Room refusing to open the database: a missing migration or a schema that doesn't match.
         * Its message names versions and tables, never rows.
         */
        DATABASE_MIGRATION(keepsMessages = true),
    }
}
