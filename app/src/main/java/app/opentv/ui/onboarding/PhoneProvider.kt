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
 * A provider sent from the phone. A first one, with no TMDB Key yet, carries straight on to the TMDB
 * Key on the phone, as the TV goes to its TMDB step: the address stays open for it.
 */
@Composable
internal fun PhoneProvider(
    viewModel: SourcesViewModel,
    offerTmdbKey: Boolean,
    onSetup: () -> Unit,
    onFinished: () -> Unit,
    onCancel: () -> Unit,
) {
    val ui by viewModel.ui.collectAsState()
    // Until the providers have loaded, it can't be known to be the first, so it isn't offered.
    val first = ui.loaded && !hasProvider(ui.sources)
    var handedOn by remember { mutableStateOf(false) }
    PhonePairingScreen(
        mode = if (offerTmdbKey && first) {
            PairingServer.Mode.PROVIDER_THEN_TMDB_KEY
        } else {
            PairingServer.Mode.PROVIDER
        },
        keepListening = { handedOn },
        // The TV stays on "Got it" while the channels load, and the phone waits with it: a
        // failed login is put right on the phone, which shows why, and the TV listens again.
        // Once the channels are in, a first provider carries on in Setup, already past its
        // first step; a later one goes to Home as before.
        onReceived = { received ->
            var setup = false
            viewModel.saveAndSync(
                received.draft,
                keepIfFails = false,
                onSetup = { setup = true },
                onChannels = { failure ->
                    // Before the answer, so leaving for the TMDB step doesn't close the address.
                    if (failure == null && setup && offerTmdbKey) handedOn = true
                    received.answer(failure)
                    if (failure == null) if (setup) onSetup() else onFinished()
                },
            )
        },
        onCancel = onCancel,
    )
}
