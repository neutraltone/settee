/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv.ui.onboarding

import androidx.compose.runtime.Composable
import app.opentv.ui.SourcesViewModel

/** A provider sent from the phone. */
@Composable
internal fun PhoneProvider(
    viewModel: SourcesViewModel,
    onSetup: () -> Unit,
    onFinished: () -> Unit,
    onCancel: () -> Unit,
) {
    PhonePairingScreen(
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
                    received.answer(failure)
                    if (failure == null) if (setup) onSetup() else onFinished()
                },
            )
        },
        onCancel = onCancel,
    )
}
