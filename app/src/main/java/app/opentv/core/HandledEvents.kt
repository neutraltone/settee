/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv.core

import android.content.Context
import io.sentry.SentryEvent
import io.sentry.SentryLevel
import io.sentry.protocol.Device
import io.sentry.protocol.Message

/**
 * What each [HandledReport] is sent as, for [CrashReports]: the one place a kind becomes an event, so
 * each kind's shape is decided once, by its type. The scrubber ([CrashScrubbing]) still sees every one.
 */
internal object HandledEvents {
    private const val TO_THE_NEAREST = 100

    /** The titles of the kinds that aren't an exception's: Sentry groups each by its title. */
    const val SLOW_JOURNEY = "SLOW_JOURNEY"
    const val VIEWER_REPORT = "VIEWER_REPORT"

    fun of(report: HandledReport): SentryEvent = when (report) {
        is CaughtError -> SentryEvent(HandledError.of(report.kind, report.error))
        is ServiceFailure -> serviceFailure(report)
        is JourneyTimer.SlowJourney -> slowJourney(report)
        is ErrorReport -> errorReport(report)
    }

    /**
     * One of Settee's own services (ADR 0010): `SERVICE operation status`, the three as tags, and the
     * stack that shows which client asked. The request and the reply were never in it.
     */
    private fun serviceFailure(failure: ServiceFailure) =
        SentryEvent(HandledError(failure.message.orEmpty(), null).apply { stackTrace = failure.stackTrace }).apply {
            setTag(CrashScrubbing.SERVICE_TAG, failure.service.tag)
            setTag(CrashScrubbing.OPERATION_TAG, failure.operation.tag)
            setTag(CrashScrubbing.STATUS_TAG, failure.status)
        }

    /**
     * A Slow Journey (ADR 0010): a message event of tags, with no stack. The journey alone titles it,
     * so Sentry groups each journey's reports as one issue.
     */
    private fun slowJourney(slow: JourneyTimer.SlowJourney) = SentryEvent().apply {
        val journey = slow.journey.tag
        message = Message().apply { formatted = "$SLOW_JOURNEY: $journey" }
        level = SentryLevel.WARNING
        setTag(CrashScrubbing.JOURNEY_TAG, journey)
        setTag(CrashScrubbing.OWN_TIME_TAG, rounded(slow.ownMillis))
        setTag(CrashScrubbing.NETWORK_WAIT_TAG, rounded(slow.networkMillis))
        slow.source?.let { setTag(CrashScrubbing.SOURCE_KIND_TAG, it.tag) }
    }

    /**
     * An Error Report: a message naming its kind, with the kind and the detail line as tags the
     * scrubber lets through (scrubbing the detail).
     */
    private fun errorReport(report: ErrorReport) = SentryEvent().apply {
        message = Message().apply { formatted = "$VIEWER_REPORT: ${report.kind}" }
        level = SentryLevel.ERROR
        setTag(CrashScrubbing.ERROR_KIND_TAG, report.kind)
        setTag(CrashScrubbing.ERROR_DETAIL_TAG, report.detail)
        report.source?.let { setTag(CrashScrubbing.SOURCE_KIND_TAG, it.tag) }
    }

    /**
     * A Slow Journey's free memory, read as it's sent: Sentry only reads it itself for a crash, since
     * collecting the rest of what comes with it is off.
     */
    fun withFreeMemory(event: SentryEvent, context: Context?) {
        if (context == null || event.getTag(CrashScrubbing.JOURNEY_TAG) == null) return
        val device = event.contexts.device ?: Device().also(event.contexts::setDevice)
        device.freeMemory = HeldCrash.At.now(context, screen = null).freeMemory
    }

    private fun rounded(millis: Long) = ((millis + TO_THE_NEAREST / 2) / TO_THE_NEAREST * TO_THE_NEAREST).toString()
}

/** What a handled error is sent as: Settee's own type, so none of the original's fields ride along. */
internal class HandledError(message: String, cause: Throwable?) : Exception(message, cause) {
    companion object {
        /** Causes copied: enough for any real chain, and an end to a looping one. */
        private const val MAX_CAUSES = 8

        /**
         * [error] as a [HandledError] named after [kind] and each class in its chain, with the same
         * stack traces, and the original messages only where [CaughtError.Kind.keepsMessages] says they're safe.
         */
        fun of(kind: CaughtError.Kind, error: Throwable): HandledError {
            fun copy(from: Throwable, depth: Int): HandledError {
                val type = from.javaClass.name
                val message = if (kind.keepsMessages) "$type: ${from.message}" else type
                val cause = from.cause?.takeIf { it !== from && depth < MAX_CAUSES }?.let { copy(it, depth + 1) }
                return HandledError(message, cause).apply { stackTrace = from.stackTrace }
            }
            val copied = copy(error, 0)
            return HandledError("${kind.name}: ${copied.message}", copied.cause).apply { stackTrace = error.stackTrace }
        }
    }
}
