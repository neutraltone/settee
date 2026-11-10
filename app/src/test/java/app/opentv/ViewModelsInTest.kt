/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.job

/**
 * View models a test builds, stopped before it closes the database. Their `stateIn(WhileSubscribed)`
 * flows outlive the test's collector, so a Room query still running when the database closed threw
 * into a later test (it once failed a release build). [stopAll] cancels each one's scope and waits
 * for in-flight queries to finish.
 */
class ViewModelsInTest {
    private val tracked = mutableListOf<ViewModel>()

    fun <VM : ViewModel> track(viewModel: VM): VM = viewModel.also { tracked += it }

    suspend fun stopAll() {
        tracked.forEach { it.viewModelScope.coroutineContext.job.cancelAndJoin() }
        tracked.clear()
    }
}
