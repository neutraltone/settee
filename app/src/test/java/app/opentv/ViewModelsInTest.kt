/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv

import android.os.Looper
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.job
import org.robolectric.Shadows.shadowOf

/**
 * View models a test builds, stopped before it closes the database. Their `stateIn(WhileSubscribed)`
 * flows outlive the test's collector, so a Room query still running when the database closed threw
 * into a later test (it once failed a release build). [stopAll] cancels each one's scope and waits
 * for in-flight queries to finish.
 */
class ViewModelsInTest {
    private val tracked = mutableListOf<ViewModel>()

    fun <VM : ViewModel> track(viewModel: VM): VM = viewModel.also { tracked += it }

    /**
     * Not a join: their work ends on the main thread, which a test's teardown runs on, so blocking it
     * to wait never let that work finish and hung the run (#547's CI). The main looper runs while
     * waiting instead, up to a limit.
     */
    fun stopAll() {
        val work = tracked.map { it.viewModelScope.coroutineContext.job }
        tracked.forEach { it.viewModelScope.cancel() }
        val onMain = Looper.myLooper() == Looper.getMainLooper()
        val until = System.currentTimeMillis() + STOP_MILLIS
        while (work.any { !it.isCompleted } && System.currentTimeMillis() < until) {
            if (onMain) shadowOf(Looper.getMainLooper()).idle()
            Thread.sleep(5)
        }
        tracked.clear()
    }

    private companion object {
        /** Long enough for a read already running to come back, short enough not to hang a run. */
        const val STOP_MILLIS = 5_000L
    }
}
