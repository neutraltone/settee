/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv.player

import app.opentv.diagnostics.PlaybackDiagnostics
import app.opentv.player.PlayerController.State
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * A [PlayerController]'s [State], published through one door, so every error shown reaches
 * Diagnostics, and ends the open being timed, rather than each place that fails remembering to.
 */
internal class PlayerStates(private val diagnostics: PlaybackDiagnostics?, private val opens: OpenTimer) {
    private val states = MutableStateFlow<State>(State.Idle)

    val flow: StateFlow<State> = states.asStateFlow()

    val value: State get() = states.value

    /** The same state published again (as a stream going idle keeps it) isn't a new error. */
    fun publish(state: State) {
        if (state is State.Error && state !== states.value) {
            diagnostics?.errorShown(state.reason)
            opens.failed(state.reason)
        }
        states.value = state
    }
}
