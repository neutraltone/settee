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
import io.sentry.SentryOptions
import io.sentry.android.core.SentryAndroid
import io.sentry.android.core.SentryAndroidOptions
import io.sentry.android.core.cache.AndroidEnvelopeCache
import io.sentry.hints.AbnormalExit
import io.sentry.hints.DiskFlushNotification
import io.sentry.hints.SubmissionResult
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
 * ([CrashScrubbing], [HeldCrash], [HandledEvents]) touch Sentry's SDK, and nothing outside `core/` does: screens, view
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
 * [background] are the same kind of seam: what counts as a freeze, and where sending a held crash runs.
 */
// The spec's one deep module: consent, held crashes, handled errors and Sentry's setup in one place.
class CrashReports(
    private val settings: AppSettings,
    private val to: Destination,
    private val release: String,
    private val environment: String,
    private val freezes: Freezes = Freezes(),
    private val background: Executor = Executors.newSingleThreadExecutor(),
) {
    /**
     * Where reports go: the project [dsn] names, which is blank in a build without Settee's key (so
     * none go at all), and, for a test, an [outbox] that takes the place of Sentry's own transport.
     * [queue] is where each envelope waits its turn to go to the outbox, as Sentry's own transport
     * queues them: a test can hold it, to turn reports off while one waits. [cap] is how many
     * reports that aren't crashes or freezes the project takes from this install a day (ADR 0010).
     */
    class Destination(
        val dsn: String,
        val outbox: Outbox? = null,
        val queue: Executor = Executor(Runnable::run),
        val cap: DailyCap = DailyCap(),
    )

    /** Where envelopes go instead of Sentry: each one serialized, as it would be sent. */
    fun interface Outbox {
        fun send(envelope: String)
    }

    /**
     * What counts as a freeze while reports are off: the main thread stuck for [afterMillis] (as
     * Sentry's own default) and Android agreeing it's [notResponding]. [held] runs once one is held.
     * While they're on, Sentry catches freezes, and each is noted on [elapsed]'s clock.
     */
    class Freezes(
        val afterMillis: Long = FREEZE_MILLIS,
        val notResponding: (Context) -> Boolean = FreezeWatch::androidSaysNotResponding,
        val held: () -> Unit = {},
        /** The clock [JourneyTimer] times journeys on, to tell whether one froze. */
        val elapsed: () -> Long = SystemClock::elapsedRealtime,
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

    // When Sentry last caught the app frozen as it happened, on the [elapsed] clock: a slow journey that
    // froze is the freeze's.
    @Volatile private var frozeAt = Long.MIN_VALUE

    private var started = false

    private val _askAboutHeld = MutableStateFlow(false)

    /**
     * Whether to ask, on this launch, about a crash or freeze held while reports were off. Never in a
     * build without a DSN, never once reports are on, and never after two Don't sends.
     */
    val askAboutHeld: StateFlow<Boolean> = _askAboutHeld.asStateFlow()

    private var held: HeldCrashes? = null

    // The event being sent on the viewer's say-so: the one the before-send hook lets through while
    // reports are off, with the screen it gives back.
    @Volatile private var sendingAsked: Asked? = null

    /** What's being sent on the viewer's say-so, by its event's id. */
    private class Asked(val eventId: SentryId?, val what: Outgoing)

    /** What the viewer said to send: one held crash or one Error Report, never both or neither. */
    private sealed interface Outgoing {
        fun toEvent(): SentryEvent

        /** A held crash, which carries its own screen and free memory, given back as it goes. */
        class Held(val crash: HeldCrash) : Outgoing {
            override fun toEvent() = crash.toEvent()
        }

        /** An Error Report, from the [screen] it was pressed on, not whichever is open by the time it goes. */
        class Viewer(val report: ErrorReport, val screen: String?) : Outgoing {
            override fun toEvent() = HandledEvents.of(report)
        }
    }

    private val mayHold: Boolean get() = available && settings.crashReportsRefusals < MOST_REFUSALS

    // Whether a handled error goes now: a build that has reports, the viewer's yes, and Sentry started.
    private val reporting: Boolean get() = available && on.value && Sentry.isEnabled()

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
            Answer.SEND -> held?.take()?.let { send(Outgoing.Held(it)) }
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
                held?.take()?.let { send(Outgoing.Held(it)) }
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
     * Reports [report], one of the handled kinds ([HandledReport]), while Crash Reports are on,
     * through the same scrubber as a crash. Off, it's dropped, never held for the after-crash question:
     * that's for crashes the viewer saw, and a handled error was already shown as one.
     *
     * An [ErrorReport] is the exception: one the viewer pressed Send report for (ADR 0010), sent whether
     * or not Crash Reports are on and without turning them on, as the after-crash Send is. Never in a
     * build without a DSN, which never offers it.
     *
     * Every kind counts towards the install's daily [DailyCap], and once it's reached the rest of
     * the day's are dropped. Crashes and freezes don't come through here, and are never capped.
     */
    fun report(report: HandledReport) {
        when {
            // Over the day's cap it goes no further, but the viewer was still heard: Send report says Sent.
            report is ErrorReport -> if (available && to.cap.take(settings)) send(Outgoing.Viewer(report, screen))
            !reporting -> Unit
            // A journey that froze is the freeze's: that was reported, and says more.
            report is JourneyTimer.SlowJourney && frozeAt in report.startedAt..report.endedAt -> Unit
            // Counted only once it's going: one dropped while reports are off, or as a freeze's, isn't.
            !to.cap.take(settings) -> Unit
            else -> Sentry.captureEvent(HandledEvents.of(report))
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
            options.setTransportGate { on.value || sendingAsked != null }
            options.beforeSend = SentryOptions.BeforeSendCallback { event, hint ->
                // Checked again here: a crash can land between turning reports off and Sentry closing.
                // While off, the held crash or Error Report being sent is the only event let through:
                // a handled error or anything else is dropped, never held, since only the crash
                // handler and the freeze watch hold. A freeze or crash Sentry finds in Android's exit
                // history from before reports were turned on happened while the viewer hadn't said yes.
                val exitedAt = (HintUtils.getSentrySdkHint(hint) as? AbnormalExit)?.timestamp()
                // A freeze caught as it happens is one a journey still running could be slow for. Only
                // before Android 11 does Sentry catch them so; from 11 on it reads them from the exit
                // history at the next launch, and those only ever killed the process: the journey they
                // froze never ended to be reported, and this launch's journeys weren't theirs.
                val frozeNow = exitedAt == null && event.exceptions.orEmpty().any { it.mechanism?.type == ANR_MECHANISM }
                if (frozeNow) frozeAt = freezes.elapsed()
                val fromBefore = exitedAt != null && exitedAt < settings.crashReportsOnSince
                val asked = sendingAsked?.takeIf { it.eventId == event.eventId }?.what
                when {
                    asked is Outgoing.Held -> CrashScrubbing.event(event, settings.crashReportsInstallId, asked.crash.at.screen)
                        .also { asked.crash.restoreAt(it) }
                    asked is Outgoing.Viewer -> CrashScrubbing.event(event, settings.crashReportsInstallId, asked.screen)
                    !on.value || fromBefore -> null
                    else -> CrashScrubbing.event(event, settings.crashReportsInstallId, screen)
                        .also { HandledEvents.withFreeMemory(it, context) }
                }
            }
            to.outbox?.let { options.setTransportFactory(OutboxTransport.Factory(it, to.queue)) }
        }
        started = true
    }

    // Off the main thread: starting Sentry and waiting for the report to go both take a while.
    private fun send(what: Outgoing) = background.execute {
        val event = what.toEvent()
        sendingAsked = Asked(event.eventId, what)
        startSentry()
        Sentry.captureEvent(event)
        if (!on.value) {
            // Still off: Sentry stops again once it's gone. Kept, if it couldn't go, for the next time
            // Sentry starts, which is the viewer's say too.
            Sentry.flush(FLUSH_MILLIS)
            stopSentry(throwAwayWaiting = false)
        }
        sendingAsked = null
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
