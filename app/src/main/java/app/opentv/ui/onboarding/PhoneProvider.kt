/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv.ui.onboarding

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import app.opentv.pairing.PairingServer
import app.opentv.ui.SourcesViewModel
import app.opentv.ui.hasProvider

/**
 * A provider sent from the phone. A first one, in a build with OpenSubtitles, carries straight on to
 * OpenSubtitles' sign-in on the phone, as the TV goes to that step: the address stays open for it.
 */
@Composable
internal fun PhoneProvider(viewModel: SourcesViewModel, onSetup: () -> Unit, onCancel: () -> Unit) {
    val ui by viewModel.ui.collectAsState()
    // The server starts once, for what it was started for: so not until the providers have loaded,
    // or a first provider couldn't be told from a later one and wouldn't carry on to OpenSubtitles.
    if (!ui.loaded) {
        PairingStarting()
        return
    }
    val carriesOn = remember { viewModel.graph.offersOpenSubtitles() && !hasProvider(ui.sources) }
    var handedOn by remember { mutableStateOf(false) }
    PhonePairingScreen(
        mode = PairingServer.Mode.Provider(thenOpenSubtitles = carriesOn),
        keepListening = { handedOn },
        // The TV stays on "Got it" while the channels load, and the phone waits with it: a
        // failed login is put right on the phone, which shows why, and the TV listens again.
        // Once the channels are in, a first provider carries on in Setup, already past its
        // first step; a later one leaves through the add page's route, as one typed on the TV does.
        onReceived = { received ->
            var setup = false
            viewModel.saveAndSync(
                received.draft,
                keepIfFails = false,
                onSetup = { setup = true },
                onChannels = { failure ->
                    // Before the answer, so leaving for the OpenSubtitles step doesn't close the address.
                    if (failure == null && setup && carriesOn) handedOn = true
                    received.answer(failure)
                    if (failure == null && setup) onSetup()
                },
            )
        },
        onCancel = onCancel,
    )
}
