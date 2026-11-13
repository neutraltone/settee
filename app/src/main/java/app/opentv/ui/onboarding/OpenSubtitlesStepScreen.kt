/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv.ui.onboarding

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import app.opentv.R
import app.opentv.core.ServiceLocator
import app.opentv.data.repo.OpenSubtitles
import app.opentv.ui.design.PillButton
import app.opentv.ui.settings.OpenSubtitlesAttribution
import app.opentv.ui.settings.OpenSubtitlesSignInRows

/**
 * Setup's optional OpenSubtitles step, straight after the TMDB step whether a key was added or not,
 * laid out as that step is: the phone's QR code on the left, and on the right what an account gives,
 * Sign in on this TV, and Not now. Signing in moves on by itself, since there's nothing more to read
 * here; [onDone] goes on, signed in or not.
 */
@Composable
fun OpenSubtitlesStepScreen(graph: ServiceLocator.Graph, openSubtitles: OpenSubtitles, onDone: () -> Unit) {
    OpenSubtitlesSignInRows(
        openSubtitles.account,
        onboarding = true,
        graph = graph,
        onSignedIn = onDone,
        onSkipped = onDone,
    ) { code, rows ->
        StepLayout(
            title = stringResource(R.string.opensubtitles_step_title),
            help = stringResource(R.string.opensubtitles_step_body),
            attribution = { OpenSubtitlesAttribution() },
            code = { code(true) },
            rows = rows,
            actions = {
                PillButton(
                    stringResource(R.string.common_not_now),
                    onClick = onDone,
                    modifier = Modifier.takesHighlight(),
                )
            },
        )
    }
}

/** Whether Setup offers the OpenSubtitles step: a build with Settee's key, on a box not yet signed in. */
fun ServiceLocator.Graph.offersOpenSubtitles(): Boolean =
    openSubtitles.let { it != null && it.account.signedInAs.value == null }
