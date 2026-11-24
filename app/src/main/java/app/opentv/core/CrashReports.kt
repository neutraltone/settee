/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv.core

import android.content.Context
import android.os.SystemClock
import io.sentry.Hint
import io.sentry.ITransportFactory
import io.sentry.RequestDetails
import io.sentry.Sentry
import io.sentry.SentryEnvelope
import io.sentry.SentryEvent
import io.sentry.SentryLevel
import io.sentry.SentryOptions
import io.sentry.android.core.SentryAndroid
import io.sentry.android.core.SentryAndroidOptions
import io.sentry.android.core.cache.AndroidEnvelopeCache
import io.sentry.hints.AbnormalExit
import io.sentry.hints.DiskFlushNotification
import io.sentry.hints.SubmissionResult
import io.sentry.protocol.Device
import io.sentry.protocol.Message
import io.sentry.protocol.SentryId
import io.sentry.transport.ITransport
import io.sentry.transport.RateLimiter
import io.sentry.util.HintUtils
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.concurrent.Executor
import java.util.concurrent.Executors
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Crash Reports (ADR 0009): crashes and freezes sent to Sentry, only from a build with Settee's
 * DSN and only while the viewer has them [on], with nothing in them that could identify the
 * viewer or their Provider ([CrashScrubbing]). Only this and its own helpers in `core/`
 * ([CrashScrubbing], [HeldCrash]) touch Sentry's SDK, and nothing outside `core/` does: screens, view
 * models and the app go through this, so a rule changes in one place (CrashReportsBoundaryTest).
 *
 * Sentry is started here, by hand, never by its auto-init content provider (removed in the
 * manifest), so nothing of it runs before the DSN and the viewer's choice have been checked. A
 * build without a DSN, or a device that hasn't said yes, costs one setting read at launch.
 *
 * While they're off, a crash or freeze is held on the device instead ([HeldCrash]), and the next
 * launch asks the viewer whether to send it ([askAboutHeld], [answer]). Holding needs neither Sentry
 * nor anything at launch but an uncaught-exception handler and a check for the held file. Freezes are
 * read back from Android's exit history off the main thread ([PastFreezes]), or, before Android 11,
 * watched for by a [FreezeWatch] that starts once the launch has opened the first screen (ADR 0009 has
 * the cost).
 *
 * [to] says where reports go: Settee's Sentry project, or a test's [Outbox] in place of Sentry's own
 * transport, so a test can see each envelope exactly as it would leave the device. [freezes] and
 * [background] are the same kind of seam: what counts as a freeze, and where sending a held crash runs,
 * and [elapsed] is the clock [JourneyTimer] times journeys on, to tell whether one froze.
 */
// The spec's one deep module: consent, held crashes, handled errors and Sentry's setup in one place.
class CrashReports(
    private val settings: AppSettings,
    private val to: Destination,
    private val release: String,
    private val environment: String,
    private val freezes: Freezes = Freezes(),
    private val background: Executor = Executors.newSingleThreadExecutor(),
    private val elapsed: () -> Long = SystemClock::elapsedRealtime,
) {
    /**
     * Where reports go: the project [dsn] names, which is blank in a build without Settee's key (so
     * none go at all), and, for a test, an [outbox] that takes the place of Sentry's own transport.
     * [queue] is where each envelope waits its turn to go to the outbox, as Sentry's own transport
     * queues them: a test can hold it, to turn reports off while one waits.
     */
    class Destination(val dsn: String, val outbox: Outbox? = null, val queue: Executor = Executor(Runnable::run))

    /** Where envelopes go instead of Sentry: each one serialized, as it would be sent. */
    fun interface Outbox {
        fun send(envelope: String)
    }

    /**
     * What counts as a freeze while reports are off: the main thread stuck for [afterMillis] (as
     * Sentry's own default) and Android agreeing it's [notResponding]. [held] runs once one is held.
     */
    class Freezes(
        val afterMillis: Long = FREEZE_MILLIS,
        val notResponding: (Context) -> Boolean = FreezeWatch::androidSaysNotResponding,
        val held: () -> Unit = {},
    )

    /** The viewer's answers to the question after a crash. */
    enum class Answer {
        /** Send this one report; reports stay off. */
        SEND,

        /** Throw it away. After two, the question isn't asked again. */
        DONT_SEND,

        /** Send it, and turn reports on. */
        ALWAYS_SEND,
    }

    /** Whether this build can send Crash Reports at all. Without, nothing offers them. */
    val available: Boolean = to.dsn.isNotBlank()

    /** Whether this device sends Crash Reports: the viewer's choice, off until they turn it on. */
    val on: StateFlow<Boolean> = settings.crashReportsOn

    /** Whether Setup offers its Crash Reports step: a build that has them, on a device that hasn't said yes. */
    val offersSetupStep: Boolean get() = available && !on.value

    @Volatile private var context: Context? = null

    @Volatile private var cacheDir: String? = null

    @Volatile private var screen: String? = null

    // When Sentry last caught the app frozen, on the [elapsed] clock: a slow journey that froze is the freeze's.
    @Volatile private var frozeAt = Long.MIN_VALUE

    private var started = false

    private val _askAboutHeld = MutableStateFlow(false)

    /**
     * Whether to ask, on this launch, about a crash or freeze held while reports were off. Never in a
     * build without a DSN, never once reports are on, and never after two Don't sends.
     */
    val askAboutHeld: StateFlow<Boolean> = _askAboutHeld.asStateFlow()

    private var held: HeldCrashes? = null

    // The held crash being sent, by its event's id: the one event the before-send hook lets through
    // while reports are off, and the one it gives back its own screen and free memory.
    @Volatile private var sendingHeld: Pair<SentryId?, HeldCrash>? = null

    private val mayHold: Boolean get() = available && settings.crashReportsRefusals < MOST_REFUSALS

    /**
     * Called once, from `OpenTvApp.onCreate`: starts Sentry if the build and the viewer allow it, or
     * else starts holding crashes and checks for one held last time.
     */
    fun start(context: Context) {
        val app = context.applicationContext
        this.context = app
        val held = HeldCrashes(File(app.noBackupFilesDir, HELD_FILE), app, release, freezes, { screen }) {
            !on.value && mayHold
        }
        this.held = held
        // Freezes only while off: while on, Sentry watches for them.
        if (mayHold) held.start(watchFreezes = !on.value)
        if (available && on.value) startSentry()
        _askAboutHeld.value = mayHold && !on.value && held.any
        if (mayHold && !on.value && PastFreezes.kept) {
            // Off the main thread: the exit history is a call to Android, and the question can wait a moment.
            background.execute {
                val since = settings.crashReportsLookedBackTo
                settings.crashReportsLookedBackTo = System.currentTimeMillis()
                if (held.lookBack(since)) _askAboutHeld.value = mayHold && !on.value
            }
        }
    }

    /** The viewer's [answer] to [askAboutHeld]: the held report sent, thrown away, or sent with reports on. */
    fun answer(answer: Answer) {
        _askAboutHeld.value = false
        when (answer) {
            Answer.SEND -> held?.take()?.let(::send)
            Answer.DONT_SEND -> if (held?.take() != null) settings.addCrashReportsRefusal()
            // Which sends the held one, as turning them on anywhere does.
            Answer.ALWAYS_SEND -> setOn(true)
        }
    }

    /** Asked again next launch: Back on the question, which isn't an answer. */
    fun askLater() {
        _askAboutHeld.value = false
    }

    /**
     * Stops holding and watching, as the process ending does. Only a test needs it, to be the next
     * launch in the same process.
     */
    internal fun close() {
        stopSentry(throwAwayWaiting = false)
        held?.stop()
    }

    /**
     * Turns Crash Reports on or off for this device. On makes the install id, if it's the first time,
     * starts Sentry, and sends a crash held while they were off: saying yes covers it, as Always send
     * does. Off stops Sentry and throws away anything held or still waiting to go.
     */
    fun setOn(on: Boolean) {
        if (on) settings.makeCrashReportsInstallId()
        settings.setCrashReportsOn(on)
        _askAboutHeld.value = false
        if (on) {
            held?.stopWatching()
            if (available) {
                startSentry()
                held?.take()?.let(::send)
            }
        } else {
            held?.discard()
            stopSentry()
            // A freeze Android recorded while they were on isn't one to hold.
            settings.crashReportsLookedBackTo = System.currentTimeMillis()
            if (mayHold) held?.start(watchFreezes = true)
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

        /**
         * A journey Settee's own part of which was clearly too slow (ADR 0010), sent by [slowJourney]
         * with no error at all: only the journey, its times and the Source's kind.
         */
        SLOW_JOURNEY(keepsMessages = false),
    }

    /**
     * Reports [error], a handled error of [kind], while Crash Reports are on, through the same
     * scrubber as a crash. Off, it's dropped, never held for the after-crash question: that's for
     * crashes the viewer saw, and a handled error was already shown as one.
     */
    fun report(kind: Handled, error: Throwable) {
        if (!available || !on.value || !Sentry.isEnabled()) return
        Sentry.captureException(HandledError.of(kind, error))
    }

    /**
     * Reports [slow], a journey [JourneyTimer] found too slow, while Crash Reports are on: its name, Settee's
     * own time and the network wait (each to the nearest 100 ms), and the Source's kind, beside the screen
     * and free memory every report carries. Not if the app froze during it: the freeze is reported, and
     * says more. [JourneyTimer] sends each journey at most once a process.
     */
    fun slowJourney(slow: JourneyTimer.SlowJourney) {
        if (!available || !on.value || !Sentry.isEnabled()) return
        if (frozeAt in slow.startedAt..slow.endedAt) return
        val journey = slow.journey.tag
        Sentry.captureEvent(
            SentryEvent().apply {
                // The journey alone, so Sentry groups each journey's reports as one issue.
                message = Message().apply { formatted = "${Handled.SLOW_JOURNEY.name}: $journey" }
                level = SentryLevel.WARNING
                setTag(CrashScrubbing.JOURNEY_TAG, journey)
                setTag(CrashScrubbing.OWN_TIME_TAG, toTheNearest100(slow.ownMillis).toString())
                setTag(CrashScrubbing.NETWORK_WAIT_TAG, toTheNearest100(slow.networkMillis).toString())
                slow.source?.let { setTag(CrashScrubbing.SOURCE_KIND_TAG, it) }
            },
        )
    }

    /**
     * A Slow Journey's free memory, read as it's sent: Sentry only reads it itself for a crash, since
     * collecting the rest of what comes with it is off ([errorsAndFreezesOnly]).
     */
    private fun withFreeMemory(event: SentryEvent) {
        val context = context ?: return
        if (event.getTag(CrashScrubbing.JOURNEY_TAG) == null) return
        val device = event.contexts.device ?: Device().also(event.contexts::setDevice)
        device.freeMemory = HeldCrash.At.now(context, screen).freeMemory
    }

    private fun toTheNearest100(millis: Long) = (millis + 50) / 100 * 100

    /** What a handled error is sent as: Settee's own type, so none of the original's fields ride along. */
    class HandledError internal constructor(message: String, cause: Throwable?) : Exception(message, cause) {
        internal companion object {
            /** Causes copied: enough for any real chain, and an end to a looping one. */
            private const val MAX_CAUSES = 8

            /**
             * [error] as a [HandledError] named after [kind] and each class in its chain, with the same
             * stack traces, and the original messages only where [Handled.keepsMessages] says they're safe.
             */
            fun of(kind: Handled, error: Throwable): HandledError {
                fun copy(from: Throwable, depth: Int): HandledError {
                    val type = from.javaClass.name
                    val message = if (kind.keepsMessages) "$type: ${from.message}" else type
                    val cause = from.cause?.takeIf { it !== from && depth < MAX_CAUSES }?.let { copy(it, depth + 1) }
                    return HandledError(message, cause).apply { stackTrace = from.stackTrace }
                }
                val copied = copy(error, 0)
                return HandledError("${kind.name}: ${copied.message}", copied.cause).apply {
                    stackTrace =
                        error.stackTrace
                }
            }
        }
    }

    @Synchronized
    private fun startSentry() {
        val context = context ?: return
        if (started) return
        SentryAndroid.init(context) { options ->
            options.dsn = to.dsn
            options.release = release
            options.environment = environment
            cacheDir = options.cacheDirPath
            options.errorsAndFreezesOnly()
            // Nothing leaves while reports are off, unless it's the held crash the viewer said to send:
            // checked as each envelope's turn to go comes, so one still queued when they're turned
            // off stays behind. In place of Sentry's own gate, which waits for a connection; offline,
            // an envelope fails to go and is kept for later, as it would be anyway.
            options.setTransportGate { on.value || sendingHeld != null }
            options.beforeSend = SentryOptions.BeforeSendCallback { event, hint ->
                // Checked again here: a crash can land between turning reports off and Sentry closing.
                // While off, the held crash being sent is the only event let through: a handled error
                // or anything else is dropped, never held, since only the crash handler and the
                // freeze watch hold. A freeze or crash Sentry finds in Android's exit history from
                // before reports were turned on happened while the viewer hadn't said yes.
                if (event.exceptions.orEmpty().any { it.mechanism?.type == ANR_MECHANISM }) frozeAt = elapsed()
                val exitedAt = (HintUtils.getSentrySdkHint(hint) as? AbnormalExit)?.timestamp()
                val fromBefore = exitedAt != null && exitedAt < settings.crashReportsOnSince
                val held = sendingHeld?.takeIf { it.first == event.eventId }?.second
                when {
                    held != null -> CrashScrubbing.event(event, settings.crashReportsInstallId, held.at.screen)
                        .also(held::restoreAt)
                    !on.value || fromBefore -> null
                    else -> CrashScrubbing.event(event, settings.crashReportsInstallId, screen).also(::withFreeMemory)
                }
            }
            to.outbox?.let { options.setTransportFactory(OutboxTransport.Factory(it, to.queue)) }
        }
        started = true
    }

    // Off the main thread: starting Sentry and waiting for the report to go both take a while.
    private fun send(crash: HeldCrash) = background.execute {
        val event = crash.toEvent()
        sendingHeld = event.eventId to crash
        startSentry()
        Sentry.captureEvent(event)
        if (!on.value) {
            // Still off: Sentry stops again once it's gone. Kept, if it couldn't go, for the next time
            // Sentry starts, which is the viewer's say too.
            Sentry.flush(FLUSH_MILLIS)
            stopSentry(throwAwayWaiting = false)
        }
        sendingHeld = null
    }

    @Synchronized
    private fun stopSentry(throwAwayWaiting: Boolean = true) {
        // Closing flushes what's queued, but the transport gate reads the consent, already off by now
        // when turning reports off, so nothing queued gets out: it stays in the cache, emptied below.
        if (started) Sentry.close()
        started = false
        if (!throwAwayWaiting) return
        // Envelopes Sentry kept to send later, such as one written as the app crashed offline. Where
        // Sentry keeps them by default when it hasn't started in this process. Its markers of which
        // exits it has looked at stay, so it doesn't look at them again.
        val kept = cacheDir?.let(::File) ?: context?.cacheDir?.resolve("sentry")
        kept?.listFiles()?.filter { it.name !in SENTRY_MARKERS }?.forEach(File::deleteRecursively)
    }

    private companion object {
        const val HELD_FILE = "crash-reports/held.json"
        const val MOST_REFUSALS = 2
        const val FREEZE_MILLIS = 5_000L
        const val FLUSH_MILLIS = 15_000L

        /** How Sentry marks a freeze it caught while the app was running. */
        const val ANR_MECHANISM = "ANR"

        /** Files in Sentry's cache that only say which of Android's exit records it has looked at. */
        val SENTRY_MARKERS = setOf(
            AndroidEnvelopeCache.LAST_ANR_REPORT,
            AndroidEnvelopeCache.LAST_TOMBSTONE_REPORT,
            AndroidEnvelopeCache.LAST_MEMORY_LIMITER_REPORT,
        )
    }

    /**
     * Sentry's transport, swapped for one that hands each envelope to an [Outbox] once its turn on
     * [queue] comes, if the transport gate lets it go then, as Sentry's own transport does.
     */
    private class OutboxTransport(
        private val options: SentryOptions,
        private val outbox: Outbox,
        private val queue: Executor,
    ) : ITransport {
        class Factory(private val outbox: Outbox, private val queue: Executor) : ITransportFactory {
            override fun create(options: SentryOptions, requestDetails: RequestDetails): ITransport =
                OutboxTransport(options, outbox, queue)
        }

        override fun send(envelope: SentryEnvelope, hint: Hint) = queue.execute {
            val going = options.transportGate.isConnected
            if (going) {
                val bytes = ByteArrayOutputStream()
                options.serializer.serialize(envelope, bytes)
                outbox.send(bytes.toString(Charsets.UTF_8.name()))
            }
            // What Sentry's own transport tells a crash waiting to hear it's gone, so it stops waiting.
            val sdkHint = HintUtils.getSentrySdkHint(hint)
            (sdkHint as? SubmissionResult)?.setResult(going)
            (sdkHint as? DiskFlushNotification)?.markFlushed()
        }

        override fun flush(timeoutMillis: Long) = Unit

        override fun getRateLimiter(): RateLimiter? = null

        override fun close(isRestarting: Boolean) = Unit

        override fun close() = Unit
    }
}

/** Errors and freezes only (ADR 0009): everything else Sentry can collect or send, off. */
private fun SentryAndroidOptions.errorsAndFreezesOnly() {
    // No tracing, profiling, replay, screenshots, view hierarchy, breadcrumbs of any kind, sessions or
    // anything sendDefaultPii would add.
    isSendDefaultPii = false
    isAnrEnabled = true
    tracesSampleRate = null
    profilesSampleRate = null
    isEnableAppStartProfiling = false
    isEnableAutoActivityLifecycleTracing = false
    isEnableUserInteractionTracing = false
    isEnableFramesTracking = false
    sessionReplay.sessionSampleRate = 0.0
    sessionReplay.onErrorSampleRate = 0.0
    isAttachScreenshot = false
    isAttachViewHierarchy = false
    isAttachThreads = false
    isAttachServerName = false
    isEnableActivityLifecycleBreadcrumbs = false
    isEnableAppLifecycleBreadcrumbs = false
    isEnableSystemEventBreadcrumbs = false
    isEnableAppComponentBreadcrumbs = false
    isEnableNetworkEventBreadcrumbs = false
    isEnableUserInteractionBreadcrumbs = false
    maxBreadcrumbs = 0
    isEnableAutoSessionTracking = false
    isSendClientReports = false
    isEnableNdk = false
    isEnableRootCheck = false
    isCollectAdditionalContext = false
    // Sentry reads Android's exit history at start: only its newest freeze, and never the thread dump
    // as a raw file, which would bypass the allow-list.
    isReportHistoricalAnrs = false
    isAttachAnrThreadDump = false

    beforeSendTransaction = SentryOptions.BeforeSendTransactionCallback { _, _ -> null }
    beforeBreadcrumb = SentryOptions.BeforeBreadcrumbCallback { breadcrumb, _ ->
        CrashScrubbing.breadcrumb(breadcrumb)
    }
}
