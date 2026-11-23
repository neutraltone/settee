/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv.core

import android.content.Context
import android.os.Looper
import io.sentry.SentryEvent
import io.sentry.SentryLevel
import io.sentry.protocol.Mechanism
import io.sentry.protocol.SentryException
import io.sentry.protocol.SentryStackFrame
import io.sentry.protocol.SentryStackTrace
import java.io.File
import java.util.Date
import org.json.JSONArray
import org.json.JSONObject

/**
 * A crash or freeze that happened while Crash Reports were off, kept on the device until the viewer
 * says what to do with it (ADR 0009). Part of [CrashReports]; nothing else uses it.
 *
 * Kept as Settee's own few lines of JSON rather than a Sentry envelope: Sentry isn't running while
 * reports are off, and starting it just to write one would put it back on every launch. The messages
 * are [CrashScrubbing.scrub]bed before they're written, so not even the device keeps a login here.
 */
internal data class HeldCrash(
    val freeze: Boolean,
    val timeMillis: Long,
    /** The release it happened in, which an update before the viewer answers mustn't change. */
    val release: String,
    /** Outermost first, then each cause. */
    val exceptions: List<Thrown>,
) {
    data class Thrown(val type: String, val message: String?, val frames: List<StackTraceElement>)

    /** As Sentry would have built it at the time: an unhandled crash, or a freeze as its ANR. */
    fun toEvent(): SentryEvent = SentryEvent(Date(timeMillis)).apply {
        level = SentryLevel.FATAL
        release = this@HeldCrash.release
        // Sentry lists the root cause first.
        exceptions = this@HeldCrash.exceptions.reversed().map { thrown ->
            SentryException().apply {
                type = thrown.type.substringAfterLast('.')
                module = thrown.type.substringBeforeLast('.', "").ifEmpty { null }
                value = thrown.message
                stacktrace = SentryStackTrace(thrown.frames.reversed().map(::frame))
                mechanism = Mechanism().apply {
                    type = if (freeze) "ANR" else "UncaughtExceptionHandler"
                    isHandled = false
                }
            }
        }
    }

    private fun frame(element: StackTraceElement) = SentryStackFrame().apply {
        module = element.className
        function = element.methodName
        filename = element.fileName
        lineno = element.lineNumber.takeIf { it >= 0 }
        isNative = element.isNativeMethod
        isInApp = element.className.startsWith(OWN_CODE)
    }

    fun toJson(): String = JSONObject()
        .put("freeze", freeze)
        .put("time", timeMillis)
        .put("release", release)
        .put(
            "exceptions",
            JSONArray(
                exceptions.map { thrown ->
                    JSONObject()
                        .put("type", thrown.type)
                        .put("message", thrown.message)
                        .put(
                            "frames",
                            JSONArray(
                                thrown.frames.map { frame ->
                                    JSONObject()
                                        .put("class", frame.className)
                                        .put("method", frame.methodName)
                                        .put("file", frame.fileName)
                                        .put("line", frame.lineNumber)
                                },
                            ),
                        )
                },
            ),
        )
        .toString()

    companion object {
        private const val OWN_CODE = "app.opentv"

        // Enough to place any crash; a stack overflow's thousand repeats add nothing.
        private const val MOST_FRAMES = 250
        private const val MOST_CAUSES = 10

        /** [error], as the app crashed with it. */
        fun crash(error: Throwable, release: String, now: Long): HeldCrash {
            val chain = generateSequence(error) { it.cause.takeIf { cause -> cause !== it } }
                .take(MOST_CAUSES)
                .map { thrown ->
                    Thrown(thrown.javaClass.name, thrown.message?.let(CrashScrubbing::scrub), thrown.stackTrace.top())
                }
            return HeldCrash(freeze = false, timeMillis = now, release = release, exceptions = chain.toList())
        }

        /** The main thread stuck at [mainStack] for [afterMillis], named as Sentry names an ANR. */
        fun freeze(mainStack: Array<StackTraceElement>, afterMillis: Long, release: String, now: Long) = HeldCrash(
            freeze = true,
            timeMillis = now,
            release = release,
            exceptions = listOf(
                Thrown(
                    "io.sentry.android.core.ApplicationNotResponding",
                    "Application Not Responding for at least $afterMillis ms.",
                    mainStack.top(),
                ),
            ),
        )

        private fun Array<StackTraceElement>.top() = take(MOST_FRAMES)

        fun fromJson(json: String): HeldCrash = JSONObject(json).let { held ->
            val exceptions = held.getJSONArray("exceptions")
            HeldCrash(
                freeze = held.getBoolean("freeze"),
                timeMillis = held.getLong("time"),
                release = held.getString("release"),
                exceptions = (0 until exceptions.length()).map { i ->
                    val thrown = exceptions.getJSONObject(i)
                    val frames = thrown.getJSONArray("frames")
                    Thrown(
                        type = thrown.getString("type"),
                        message = thrown.optString("message").takeIf { thrown.has("message") },
                        frames = (0 until frames.length()).map { f ->
                            val frame = frames.getJSONObject(f)
                            StackTraceElement(
                                frame.getString("class"),
                                frame.getString("method"),
                                frame.optString("file").takeIf { frame.has("file") },
                                frame.getInt("line"),
                            )
                        },
                    )
                },
            )
        }
    }
}

/**
 * Where a [HeldCrash] waits, and what holds one: an uncaught-exception handler and, while [watching],
 * a [FreezeWatch]. One file, so a newer crash replaces an older, written whole and then moved into
 * place, so a crash while writing can't leave half of one. Each crash or freeze is held only if
 * [shouldHold] says so when it happens.
 */
internal class HeldCrashes(
    private val file: File,
    private val context: Context,
    private val release: String,
    private val freezes: CrashReports.Freezes,
    private val shouldHold: () -> Boolean,
) {
    private var handler: HoldingHandler? = null

    private var watch: FreezeWatch? = null

    val any: Boolean get() = file.exists()

    /**
     * Starts holding crashes, and freezes too if [watchFreezes]. The handler goes in before Sentry's,
     * so Sentry's, while it runs, passes each crash on to it.
     */
    fun start(watchFreezes: Boolean) {
        if (handler == null) {
            handler = HoldingHandler(Thread.getDefaultUncaughtExceptionHandler()) { error ->
                if (shouldHold()) hold(HeldCrash.crash(error, release, System.currentTimeMillis()))
            }.also(Thread::setDefaultUncaughtExceptionHandler)
        }
        if (watch == null && watchFreezes) {
            val main = Looper.getMainLooper().thread
            watch = FreezeWatch(freezes.afterMillis, { freezes.notResponding(context) }) {
                if (shouldHold()) {
                    hold(HeldCrash.freeze(main.stackTrace, freezes.afterMillis, release, System.currentTimeMillis()))
                    freezes.held()
                }
            }.also(Thread::start)
        }
    }

    /** Stops holding, as the process ending does. */
    fun stop() {
        watch?.quit()
        watch = null
        handler?.let { if (Thread.getDefaultUncaughtExceptionHandler() === it) it.restore() }
        handler = null
    }

    private fun hold(crash: HeldCrash) {
        file.parentFile?.mkdirs()
        val writing = File(file.path + ".new")
        writing.writeText(crash.toJson())
        writing.renameTo(file)
    }

    /** The held crash, which is no longer held. Null if there's none, or what's there can't be read. */
    fun take(): HeldCrash? {
        if (!file.exists()) return null
        val held = runCatching { HeldCrash.fromJson(file.readText()) }.getOrNull()
        discard()
        return held
    }

    fun discard() {
        file.delete()
    }

    /** Holds each crash, then hands it on to the handler that was there before. */
    private class HoldingHandler(
        private val previous: Thread.UncaughtExceptionHandler?,
        private val hold: (Throwable) -> Unit,
    ) : Thread.UncaughtExceptionHandler {
        override fun uncaughtException(thread: Thread, error: Throwable) {
            // Whatever goes wrong holding it, the crash carries on as it would have.
            runCatching { hold(error) }
            previous?.uncaughtException(thread, error)
        }

        fun restore() = Thread.setDefaultUncaughtExceptionHandler(previous)
    }
}
