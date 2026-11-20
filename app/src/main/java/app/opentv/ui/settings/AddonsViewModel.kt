/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv.ui.settings

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import app.opentv.core.ServiceLocator
import app.opentv.core.ViewerError
import app.opentv.data.model.StremioAddon
import app.opentv.data.remote.AddonFailure
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Backs Add-ons: the stored add-ons, and checking a new one against the live add-on before keeping it. */
class AddonsViewModel @JvmOverloads constructor(
    app: Application,
    private val graph: ServiceLocator.Graph = ServiceLocator.get(app),
) : AndroidViewModel(app) {

    val addons: StateFlow<List<StremioAddon>> = graph.settings.stremioAddons

    sealed interface Status {
        data object Idle : Status
        data object Checking : Status
        data class Added(val name: String) : Status

        /** The add-on couldn't be added, and why, worded by [app.opentv.core.ErrorWording.ofAddon]. */
        data class Failed(val error: ViewerError) : Status
    }

    private val _status = MutableStateFlow<Status>(Status.Idle)
    val status: StateFlow<Status> = _status.asStateFlow()

    /** Validate a pasted manifest URL against the live add-on, and store it if it checks out. */
    fun add(rawUrl: String) {
        val url = rawUrl.trim()
        if (url.isBlank()) return
        viewModelScope.launch {
            _status.value = Status.Checking
            // Only an AddonFailure is caught: cancellation, or a bug, isn't "can't reach the add-on".
            _status.value = try {
                val name = withContext(Dispatchers.IO) { graph.stremioClient.fetchManifest(url) }
                graph.settings.addStremioAddon(StremioAddon(manifestUrl = url, name = name))
                Status.Added(name)
            } catch (failure: AddonFailure) {
                Status.Failed(graph.errorWording.ofAddon(failure))
            }
        }
    }

    fun remove(addon: StremioAddon) = graph.settings.removeStremioAddon(addon.manifestUrl)

    fun clearStatus() {
        _status.value = Status.Idle
    }
}
