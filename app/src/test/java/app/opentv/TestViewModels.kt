/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv

import android.app.Application
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import app.opentv.core.ServiceLocator
import app.opentv.ui.ChannelsViewModel
import app.opentv.ui.VodViewModel

/**
 * A test's view models, cleared as Android clears a screen's. Clear them before closing the
 * database: their flows linger for a few seconds after the screen stops watching, and one that
 * wakes after the close fails a later test with "Database is closed".
 */
class TestViewModels {
    private val store = ViewModelStore()

    fun vod(app: Application, graph: ServiceLocator.Graph): VodViewModel = ViewModelProvider(
        store,
        object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T = VodViewModel(app, graph) as T
        },
    )[VodViewModel::class.java]

    fun channels(app: Application, graph: ServiceLocator.Graph): ChannelsViewModel = ViewModelProvider(
        store,
        object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T = ChannelsViewModel(app, graph) as T
        },
    )[ChannelsViewModel::class.java]

    fun clear() = store.clear()
}
