/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv.core

import android.content.Context
import io.sentry.Hint
import io.sentry.ITransportFactory
import io.sentry.RequestDetails
import io.sentry.Sentry
import io.sentry.SentryEnvelope
import io.sentry.SentryOptions
import io.sentry.android.core.SentryAndroid
import io.sentry.android.core.SentryAndroidOptions
import io.sentry.hints.DiskFlushNotification
import io.sentry.hints.SubmissionResult
import io.sentry.transport.ITransport
import io.sentry.transport.RateLimiter
import io.sentry.util.HintUtils
import java.io.ByteArrayOutputStream
import java.io.File
import kotlinx.coroutines.flow.StateFlow

/**
 * Crash Reports (ADR 0009): crashes and freezes sent to Sentry, only from a build with Settee's
 * [dsn] and only while the viewer has them [on], with nothing in them that could identify the
 * viewer or their Provider ([CrashScrubbing]). The only code that touches Sentry's SDK: screens,
 * view models and the app go through this, so a rule changes in one place.
 *
 * Sentry is started here, by hand, never by its auto-init content provider (removed in the
 * manifest), so nothing of it runs before the DSN and the viewer's choice have been checked. A
 * build without a DSN, or a device that hasn't said yes, costs one setting read at launch.
 *
 * [outbox] replaces Sentry's own transport, so a test can see each envelope exactly as it would
 * leave the device. Null, as in the app, sends them to Sentry.
 */
class CrashReports(
    private val settings: AppSettings,
    private val dsn: String,
    private val release: String,
    private val environment: String,
    private val outbox: Outbox? = null,
) {
    /** Where envelopes go instead of Sentry: each one serialized, as it would be sent. */
    fun interface Outbox {
        fun send(envelope: String)
    }

    /** Whether this build can send Crash Reports at all. Without, nothing offers them. */
    val available: Boolean = dsn.isNotBlank()

    /** Whether this device sends Crash Reports: the viewer's choice, off until they turn it on. */
    val on: StateFlow<Boolean> = settings.crashReportsOn

    @Volatile private var context: Context? = null

    @Volatile private var cacheDir: String? = null

    @Volatile private var screen: String? = null

    private var started = false

    /** Called once, from `OpenTvApp.onCreate`: starts Sentry if the build and the viewer allow it. */
    fun start(context: Context) {
        this.context = context.applicationContext
        if (available && on.value) startSentry()
    }

    /**
     * Turns Crash Reports on or off for this device. On makes the install id, if it's the first time,
     * and starts Sentry. Off stops it and throws away anything still waiting to go.
     */
    fun setOn(on: Boolean) {
        if (on) settings.makeCrashReportsInstallId()
        settings.setCrashReportsOn(on)
        if (on) {
            if (available) startSentry()
        } else {
            stopSentry()
        }
    }

    /**
     * The screen now open, as its navigation [route]. Only the route's name is kept, never its
     * arguments, which can name a channel or a title.
     */
    fun screenOpened(route: String?) {
        screen = route?.substringBefore('/')?.substringBefore('?')?.substringBefore('{')?.takeIf { it.isNotBlank() }
    }

    /**
     * The handled errors Settee reports (ADR 0009): only failures Settee's own code can cause. Closed
     * on purpose: a Provider's failures (HTTP errors, dead streams, web pages instead of video) and
     * playback errors have no kind here, so they can't leave the device. [keepsMessages]: whether the
     * error's own messages are sent (scrubbed) or only its classes and stack trace.
     */
    enum class Handled(internal val keepsMessages: Boolean) {
        /** A panel's reply that starts as JSON but Settee couldn't read. Its message quotes the reply, login too. */
        XTREAM_PARSE(keepsMessages = false),

        /**
         * Room refusing to open the database: a missing migration or a schema that doesn't match.
         * Its message names versions and tables, never rows.
         */
        DATABASE_MIGRATION(keepsMessages = true),
    }

    /**
     * Reports [error], a handled error of [kind], while Crash Reports are on, through the same
     * scrubber as a crash. Off, it's dropped, never held for the after-crash question: that's for
     * crashes the viewer saw, and a handled error was already shown as one.
     */
    fun report(kind: Handled, error: Throwable) {
        if (!available || !on.value || !Sentry.isEnabled()) return
        val hint = Hint().apply { set(HANDLED_HINT, kind) }
        Sentry.captureException(reported(kind, error), hint)
    }

    /**
     * [error] as a [HandledError] named after [kind] and each class in its chain, with the same stack
     * traces, and the original messages only where [Handled.keepsMessages] says they're safe.
     */
    private fun reported(kind: Handled, error: Throwable): Throwable {
        fun copy(from: Throwable, depth: Int): HandledError {
            val message = if (kind.keepsMessages) "${from.javaClass.name}: ${from.message}" else from.javaClass.name
            val cause = from.cause?.takeIf { it !== from && depth < MAX_CAUSES }?.let { copy(it, depth + 1) }
            return HandledError(message, cause).apply { stackTrace = from.stackTrace }
        }
        val copied = copy(error, 0)
        return HandledError("${kind.name}: ${copied.message}", copied.cause).apply { stackTrace = error.stackTrace }
    }

    /** What a handled error is sent as: Settee's own type, so none of the original's fields ride along. */
    class HandledError internal constructor(message: String, cause: Throwable?) : Exception(message, cause)

    @Synchronized
    private fun startSentry() {
        val context = context ?: return
        if (started) return
        SentryAndroid.init(context) { options -> configure(options) }
        started = true
    }

    @Synchronized
    private fun stopSentry() {
        if (started) Sentry.close()
        started = false
        // Envelopes Sentry kept to send later, such as one written as the app crashed offline. Where
        // Sentry keeps them by default when it hasn't started in this process.
        val kept = cacheDir?.let(::File) ?: context?.cacheDir?.resolve("sentry")
        kept?.listFiles()?.forEach(File::deleteRecursively)
    }

    private fun configure(options: SentryAndroidOptions) {
        options.dsn = dsn
        options.release = release
        options.environment = environment
        cacheDir = options.cacheDirPath

        // Errors and freezes only (ADR 0009): no tracing, profiling, replay, screenshots, view
        // hierarchy, breadcrumbs of any kind, sessions or anything sendDefaultPii would add.
        options.isSendDefaultPii = false
        options.isAnrEnabled = true
        options.tracesSampleRate = null
        options.profilesSampleRate = null
        options.isEnableAppStartProfiling = false
        options.isEnableAutoActivityLifecycleTracing = false
        options.isEnableUserInteractionTracing = false
        options.isEnableFramesTracking = false
        options.sessionReplay.sessionSampleRate = 0.0
        options.sessionReplay.onErrorSampleRate = 0.0
        options.isAttachScreenshot = false
        options.isAttachViewHierarchy = false
        options.isAttachThreads = false
        options.isAttachServerName = false
        options.isEnableActivityLifecycleBreadcrumbs = false
        options.isEnableAppLifecycleBreadcrumbs = false
        options.isEnableSystemEventBreadcrumbs = false
        options.isEnableAppComponentBreadcrumbs = false
        options.isEnableNetworkEventBreadcrumbs = false
        options.isEnableUserInteractionBreadcrumbs = false
        options.maxBreadcrumbs = 0
        options.isEnableAutoSessionTracking = false
        options.isSendClientReports = false
        options.isEnableNdk = false
        options.isEnableRootCheck = false
        options.isCollectAdditionalContext = false

        options.beforeSend = SentryOptions.BeforeSendCallback { event, _ ->
            // Checked again here: a crash can land between turning reports off and Sentry closing.
            if (!on.value) null else CrashScrubbing.event(event, settings.crashReportsInstallId, screen)
        }
        options.beforeSendTransaction = SentryOptions.BeforeSendTransactionCallback { _, _ -> null }
        options.beforeBreadcrumb = SentryOptions.BeforeBreadcrumbCallback { breadcrumb, _ ->
            CrashScrubbing.breadcrumb(breadcrumb)
        }
        outbox?.let { options.setTransportFactory(OutboxTransport.Factory(it)) }
    }

    private companion object {
        /** The hint a handled error's event carries, naming its [Handled] kind. */
        const val HANDLED_HINT = "settee.handled"

        /** Causes copied into a [HandledError]: enough for any real chain, and an end to a looping one. */
        const val MAX_CAUSES = 8
    }

    /** Sentry's transport, swapped for one that hands each envelope to an [Outbox]. */
    private class OutboxTransport(private val options: SentryOptions, private val outbox: Outbox) : ITransport {
        class Factory(private val outbox: Outbox) : ITransportFactory {
            override fun create(options: SentryOptions, requestDetails: RequestDetails): ITransport =
                OutboxTransport(options, outbox)
        }

        override fun send(envelope: SentryEnvelope, hint: Hint) {
            val bytes = ByteArrayOutputStream()
            options.serializer.serialize(envelope, bytes)
            outbox.send(bytes.toString(Charsets.UTF_8.name()))
            // What Sentry's own transport tells a crash waiting to hear it's gone, so it stops waiting.
            val sdkHint = HintUtils.getSentrySdkHint(hint)
            (sdkHint as? SubmissionResult)?.setResult(true)
            (sdkHint as? DiskFlushNotification)?.markFlushed()
        }

        override fun flush(timeoutMillis: Long) = Unit

        override fun getRateLimiter(): RateLimiter? = null

        override fun close(isRestarting: Boolean) = Unit

        override fun close() = Unit
    }
}
