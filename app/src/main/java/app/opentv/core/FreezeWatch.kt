/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv.core

import android.app.ActivityManager
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.Process

/**
 * Notices the main thread freezing while Crash Reports are off, so the freeze can be held like a
 * crash (Sentry, which watches for them while reports are on, isn't running). Part of [CrashReports].
 *
 * Every [afterMillis] it posts a tick to the main thread; a tick still waiting a round later means
 * the main thread has been stuck at least that long. That alone isn't a freeze, as a busy moment
 * with nothing waiting on it isn't one to the viewer, so it's only [frozen] once [notResponding]
 * says Android agrees, as Sentry's own watchdog does. Once per freeze, however long it lasts.
 *
 * Only before Android 11, which keeps no exit history to read a freeze back from ([PastFreezes]), and
 * only once the launch has opened the first screen ([HeldCrashes.start]). A daemon thread that starts by sleeping;
 * what it costs while it runs is a tick on the main thread every [afterMillis] (ADR 0009).
 */
internal class FreezeWatch(
    private val afterMillis: Long,
    private val notResponding: () -> Boolean,
    private val frozen: () -> Unit,
) : Thread("Settee freeze watch") {

    private val main = Handler(Looper.getMainLooper())

    @Volatile private var answered = true

    @Volatile private var running = true

    private val tick = Runnable { answered = true }

    init {
        isDaemon = true
    }

    override fun run() {
        var told = false
        while (running) {
            if (answered) {
                answered = false
                main.post(tick)
            }
            try {
                sleep(afterMillis)
            } catch (_: InterruptedException) {
                return
            }
            if (answered) {
                told = false
            } else if (!told && running && notResponding()) {
                told = true
                frozen()
            }
        }
    }

    fun quit() {
        running = false
        interrupt()
        main.removeCallbacks(tick)
    }

    companion object {
        /** Whether Android has this process down as not responding: what it shows its ANR dialog for. */
        fun androidSaysNotResponding(context: Context): Boolean {
            val manager = context.getSystemService(ActivityManager::class.java) ?: return false
            val pid = Process.myPid()
            return manager.processesInErrorState.orEmpty().any {
                it.pid == pid && it.condition == ActivityManager.ProcessErrorStateInfo.NOT_RESPONDING
            }
        }
    }
}
