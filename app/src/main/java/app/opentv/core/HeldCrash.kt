/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv.core

import android.app.ActivityManager
import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import androidx.core.content.pm.PackageInfoCompat
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
    /** What else a report says that can have changed by the time it's sent. */
    val at: At = At(),
) {
    data class Thrown(val type: String, val message: String?, val frames: List<StackTraceElement>)

    /**
     * The allow-listed details (docs/PRIVACY.md) that belong to the moment of the crash, not to the
     * launch that sends it: the screen's route name, free memory, the Android version and Settee's
     * build. Null where it isn't known, as for a freeze read back from Android's exit history.
     */
    data class At(
        val screen: String? = null,
        val freeMemory: Long? = null,
        val osVersion: String? = null,
        val osBuild: String? = null,
        val appBuild: String? = null,
    ) {
        companion object {
            /** Now, as Sentry would read each detail, with [screen] open. */
            fun now(context: Context, screen: String?) = At(
                screen = screen,
                freeMemory = runCatching {
                    val manager = context.getSystemService(ActivityManager::class.java)
                    ActivityManager.MemoryInfo().also { manager?.getMemoryInfo(it) }.availMem
                }.getOrNull(),
                osVersion = Build.VERSION.RELEASE,
                osBuild = Build.DISPLAY,
                appBuild = runCatching {
                    PackageInfoCompat.getLongVersionCode(context.packageManager.getPackageInfo(context.packageName, 0))
                        .toString()
                }.getOrNull(),
            )
        }
    }

    /**
     * Puts back, on [event] as it's about to be sent, the details from when this happened in place of
     * the sending launch's. Free memory then isn't known, so it goes rather than say today's.
     */
    fun restoreAt(event: SentryEvent) {
        val contexts = event.contexts
        contexts.device?.freeMemory = at.freeMemory
        contexts.operatingSystem?.let { os ->
            at.osVersion?.let { os.version = it }
            at.osBuild?.let { os.build = it }
        }
        contexts.app?.let { app ->
            app.appVersion = release
            at.appBuild?.let { app.appBuild = it }
        }
    }

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
            "at",
            JSONObject()
                .put("screen", at.screen)
                .put("freeMemory", at.freeMemory)
                .put("osVersion", at.osVersion)
                .put("osBuild", at.osBuild)
                .put("appBuild", at.appBuild),
        )
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

        /** [error], as the app crashed with it [at] [now]. */
        fun crash(error: Throwable, release: String, now: Long, at: At): HeldCrash {
            val chain = generateSequence(error) { it.cause.takeIf { cause -> cause !== it } }
                .take(MOST_CAUSES)
                .map { thrown ->
                    Thrown(thrown.javaClass.name, thrown.message?.let(CrashScrubbing::scrub), thrown.stackTrace.top())
                }
            return HeldCrash(freeze = false, timeMillis = now, release = release, exceptions = chain.toList(), at = at)
        }

        /**
         * The main thread stuck at [mainStack] for [afterMillis], or for as long as it took Android to
         * end Settee when that's null, named as Sentry names an ANR.
         */
        fun freeze(mainStack: List<StackTraceElement>, afterMillis: Long?, release: String, now: Long, at: At) = HeldCrash(
            freeze = true,
            timeMillis = now,
            release = release,
            exceptions = listOf(
                Thrown(
                    "io.sentry.android.core.ApplicationNotResponding",
                    afterMillis?.let { "Application Not Responding for at least $it ms." } ?: "Application Not Responding.",
                    mainStack.take(MOST_FRAMES),
                ),
            ),
            at = at,
        )

        private fun Array<StackTraceElement>.top() = take(MOST_FRAMES)

        fun fromJson(json: String): HeldCrash = JSONObject(json).let { held ->
            val exceptions = held.getJSONArray("exceptions")
            val at = held.optJSONObject("at")
            fun JSONObject.text(name: String) = optString(name).takeIf { has(name) }
            HeldCrash(
                freeze = held.getBoolean("freeze"),
                timeMillis = held.getLong("time"),
                release = held.getString("release"),
                at = At(
                    screen = at?.text("screen"),
                    freeMemory = at?.takeIf { it.has("freeMemory") }?.optLong("freeMemory"),
                    osVersion = at?.text("osVersion"),
                    osBuild = at?.text("osBuild"),
                    appBuild = at?.text("appBuild"),
                ),
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
 * Where a [HeldCrash] waits, and what holds one: an uncaught-exception handler, and for freezes either
 * Android's exit history, read back at the next launch ([lookBack], Android 11 and later), or, on
 * older Androids, a [FreezeWatch] while [start] says to watch. One file, so a newer crash replaces an
 * older, written whole and then moved into place, so a crash while writing can't leave half of one.
 * Each crash or freeze is held only if [shouldHold] says so when it happens.
 */
internal class HeldCrashes(
    private val file: File,
    private val context: Context,
    private val release: String,
    private val freezes: CrashReports.Freezes,
    private val screen: () -> String?,
    private val shouldHold: () -> Boolean,
) {
    private var handler: HoldingHandler? = null

    @Volatile private var watch: FreezeWatch? = null

    @Volatile private var watchWanted = false

    val any: Boolean get() = file.exists()

    /**
     * Starts holding crashes, and freezes too if [watchFreezes] and this Android keeps no exit history.
     * The handler goes in before Sentry's, so Sentry's, while it runs, passes each crash on to it. The
     * watch is posted to the main thread, behind the launch's own work already queued there (the first
     * screen opening), so launching pays nothing for it; and it starts by sleeping.
     */
    fun start(watchFreezes: Boolean) {
        if (handler == null) {
            handler = HoldingHandler(Thread.getDefaultUncaughtExceptionHandler()) { error ->
                if (shouldHold()) {
                    hold(HeldCrash.crash(error, release, System.currentTimeMillis(), HeldCrash.At.now(context, screen())))
                }
            }.also(Thread::setDefaultUncaughtExceptionHandler)
        }
        if (!watchFreezes || PastFreezes.kept || watchWanted) return
        watchWanted = true
        Handler(Looper.getMainLooper()).post {
            if (watchWanted && watch == null) watch = freezeWatch().also(Thread::start)
        }
    }

    private fun freezeWatch(): FreezeWatch {
        val main = Looper.getMainLooper().thread
        return FreezeWatch(freezes.afterMillis, { freezes.notResponding(context) }) {
            if (shouldHold()) {
                val at = HeldCrash.At.now(context, screen())
                hold(HeldCrash.freeze(main.stackTrace.toList(), freezes.afterMillis, release, System.currentTimeMillis(), at))
                freezes.held()
            }
        }
    }

    /**
     * Holds the newest freeze Android ended Settee for after [lookedBackTo], the last look, unless a
     * newer crash is held already. The first look (0) holds nothing: what the viewer had said before
     * then isn't known. Whether something's held now.
     */
    fun lookBack(lookedBackTo: Long): Boolean {
        if (lookedBackTo > 0 && PastFreezes.kept && shouldHold()) {
            val freeze = PastFreezes.newest(context, lookedBackTo)
            val heldAt = runCatching { HeldCrash.fromJson(file.readText()).timeMillis }.getOrNull()
            if (freeze != null && (heldAt == null || heldAt < freeze.timeMillis)) {
                // Android doesn't say which screen was open, or what was free, or which build it was:
                // the release is this one's, which an update in between would make wrong.
                hold(HeldCrash.freeze(freeze.mainStack, afterMillis = null, release, freeze.timeMillis, HeldCrash.At()))
                freezes.held()
            }
        }
        return any
    }

    /** Stops watching for freezes as they happen, as once reports are on, when Sentry watches. */
    fun stopWatching() {
        watchWanted = false
        watch?.quit()
        watch = null
    }

    /** Stops holding, as the process ending does. */
    fun stop() {
        stopWatching()
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
