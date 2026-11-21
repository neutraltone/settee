/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv

import android.app.Application
import android.os.Looper
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.viewModelScope
import app.opentv.core.ServiceLocator
import app.opentv.ui.ChannelsViewModel
import app.opentv.ui.VodViewModel
import app.opentv.ui.vod.VodPlayerViewModel
import app.opentv.update.UpdateViewModel
import kotlinx.coroutines.job
import org.robolectric.Shadows.shadowOf

/**
 * A test's view models, cleared as Android clears a screen's. Clear them before closing the
 * database: their flows linger for a few seconds after the screen stops watching, and one that
 * wakes after the close fails a later test with "Database is closed". Clearing waits for each one's
 * work to stop, since cancelling alone leaves a read already running to finish on the closed database.
 */
class TestViewModels {
    private val created = mutableListOf<ViewModel>()

    private val store = ViewModelStore()

    fun vod(app: Application, graph: ServiceLocator.Graph): VodViewModel = ViewModelProvider(
        store,
        object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T = VodViewModel(app, graph) as T
        }.tracked(),
    )[VodViewModel::class.java]

    fun channels(app: Application, graph: ServiceLocator.Graph): ChannelsViewModel = ViewModelProvider(
        store,
        object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T = ChannelsViewModel(app, graph) as T
        }.tracked(),
    )[ChannelsViewModel::class.java]

    fun vodPlayer(app: Application, graph: ServiceLocator.Graph): VodPlayerViewModel = ViewModelProvider(
        store,
        object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T = VodPlayerViewModel(app, graph) as T
        }.tracked(),
    )[VodPlayerViewModel::class.java]

    fun update(app: Application, graph: ServiceLocator.Graph): UpdateViewModel = ViewModelProvider(
        store,
        object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T = UpdateViewModel(app, graph) as T
        }.tracked(),
    )[UpdateViewModel::class.java]

    fun clear() {
        store.clear()
        // Their work finishes on the main thread, which this runs on: blocking it to wait would never
        // let that work end, so the main looper runs while waiting, up to a limit.
        val work = created.map { it.viewModelScope.coroutineContext.job }
        val until = System.currentTimeMillis() + CLEAR_MILLIS
        while (work.any { !it.isCompleted } && System.currentTimeMillis() < until) {
            shadowOf(Looper.getMainLooper()).idle()
            Thread.sleep(5)
        }
        created.clear()
    }

    private fun ViewModelProvider.Factory.tracked() = object : ViewModelProvider.Factory {
        override fun <T : ViewModel> create(modelClass: Class<T>): T =
            this@tracked.create(modelClass).also { created += it }
    }

    private companion object {
        /** Long enough for a read already running to come back, short enough not to hang a run. */
        const val CLEAR_MILLIS = 5_000L
    }
}
