/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv.ui.player

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import app.opentv.core.PipState
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged

/**
 * Runs [onGoneBehind] when the app goes behind another one, and [onBack] when it comes in front again
 * (#356). Nothing runs for picture-in-picture, which is still watching: the activity is started there.
 *
 * Without it a player carries on behind the next app: its sound plays over that app's, and live TV keeps
 * the provider's connection, which a single-connection account needs for another TV. Fire TV Sticks
 * mostly can't do picture-in-picture, so on them this is every time Home is pressed mid-programme.
 */
@Composable
fun WhenBehindAnotherApp(onGoneBehind: () -> Unit, onBack: () -> Unit) {
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val goneBehind by rememberUpdatedState(onGoneBehind)
    val cameBack by rememberUpdatedState(onBack)
    // Collected rather than read in composition: Compose stops drawing frames while the activity is
    // stopped, so a recomposition keyed on the lifecycle wouldn't run until the app was back in front.
    LaunchedEffect(lifecycle) {
        var wasBehind = false
        combine(lifecycle.currentStateFlow, PipState.inPip) { state, inPip ->
            !state.isAtLeast(Lifecycle.State.STARTED) && !inPip
        }
            .distinctUntilChanged()
            .collect { behind ->
                // A screen opened in front isn't coming back; one opened behind (restored there) has gone.
                if (behind) {
                    goneBehind()
                } else if (wasBehind) {
                    cameBack()
                }
                wasBehind = behind
            }
    }
}
