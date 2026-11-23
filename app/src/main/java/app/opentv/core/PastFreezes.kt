/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv.core

import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.content.Context
import android.os.Build
import androidx.annotation.RequiresApi

/**
 * Freezes Android ended Settee for, read back from its exit history at the next launch (Android 11
 * and later), so a freeze while Crash Reports are off can be held without anything watching the main
 * thread while Settee runs. Part of [CrashReports]: older Androids have no history, so [FreezeWatch]
 * watches there instead.
 */
internal object PastFreezes {

    /** Whether this Android keeps the history, so nothing need watch for freezes as they happen. */
    val kept: Boolean get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.R

    /** A freeze: when Android ended Settee for it, and where the main thread was stuck. */
    class Freeze(val timeMillis: Long, val mainStack: List<StackTraceElement>)

    /**
     * The newest freeze Android ended Settee for after [afterMillis], if Android kept the main thread's
     * stack for it. Null without one.
     */
    @RequiresApi(Build.VERSION_CODES.R)
    fun newest(context: Context, afterMillis: Long): Freeze? {
        val manager = context.getSystemService(ActivityManager::class.java) ?: return null
        val exit = manager.getHistoricalProcessExitReasons(context.packageName, 0, 0)
            .filter { it.reason == ApplicationExitInfo.REASON_ANR && it.timestamp > afterMillis }
            .maxByOrNull { it.timestamp } ?: return null
        val dump = runCatching { exit.traceInputStream?.bufferedReader()?.use { it.readText() } }.getOrNull()
        val stack = dump?.let(::mainStack)?.takeIf { it.isNotEmpty() } ?: return null
        return Freeze(exit.timestamp, stack)
    }

    /**
     * The main thread's stack in an ANR's thread dump: the `at` lines under `"main"`, up to the blank line
     * that ends its block. Native frames and lock lines (`- waiting on`, `native:`) are left out.
     */
    fun mainStack(dump: String): List<StackTraceElement>? {
        val lines = dump.lines()
        val main = lines.indexOfFirst { it.startsWith("\"main\"") }
        if (main < 0) return null
        return lines.drop(main + 1).takeWhile { it.isNotBlank() }.mapNotNull(::frame)
    }

    // `  at app.opentv.ui.Home.load(Home.kt:42)`, `(Native method)` or `(Unknown Source)`.
    private val FRAME = Regex("""^\s+at (.+)\.([^.(]+)\(([^:)]*)(?::(\d+))?\)$""")

    private fun frame(line: String): StackTraceElement? {
        val match = FRAME.matchEntire(line) ?: return null
        val (type, method, file, number) = match.destructured
        val native = file == "Native method"
        return StackTraceElement(
            type,
            method,
            file.takeUnless { native || it == "Unknown Source" || it.isEmpty() },
            number.toIntOrNull() ?: if (native) NATIVE_LINE else -1,
        )
    }

    /** What [StackTraceElement] takes as a native method's line. */
    private const val NATIVE_LINE = -2
}
