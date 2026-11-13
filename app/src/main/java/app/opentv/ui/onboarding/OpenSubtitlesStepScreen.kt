/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv.ui.onboarding

import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.opentv.R
import app.opentv.core.ServiceLocator
import app.opentv.data.repo.OpenSubtitles
import app.opentv.ui.design.PillButton
import app.opentv.ui.design.SetteeColors
import app.opentv.ui.design.SetteeType
import app.opentv.ui.design.Text
import app.opentv.ui.settings.OpenSubtitlesAttribution
import app.opentv.ui.settings.OpenSubtitlesSignInRows

/**
 * Setup's optional OpenSubtitles step, straight after the TMDB step whether a key was added or not,
 * laid out as that step is: the phone's QR code on the left, and on the right what an account gives,
 * Sign in on this TV, and Not now. Signed in, from the phone or the TV, it says so and waits for Carry
 * on, as the TMDB step does once its key is saved: moving on by itself left nothing on screen long
 * enough to read. [onDone] goes on, signed in or not.
 */
@Composable
fun OpenSubtitlesStepScreen(graph: ServiceLocator.Graph, openSubtitles: OpenSubtitles, onDone: () -> Unit) {
    var signedIn by remember { mutableStateOf(false) }
    if (signedIn) {
        SignedIn(openSubtitles, onDone)
        return
    }
    OpenSubtitlesSignInRows(
        openSubtitles.account,
        onboarding = true,
        graph = graph,
        onSignedIn = { signedIn = true },
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

/**
 * Signed in: who as, and what it gives, in the sign-in rows' place, with Carry on where Not now was.
 * The QR code's space stays, empty, as the TMDB step's does once its key is saved.
 */
@Composable
private fun SignedIn(openSubtitles: OpenSubtitles, onDone: () -> Unit) {
    val name by openSubtitles.account.signedInAs.collectAsState()
    StepLayout(
        title = stringResource(R.string.opensubtitles_step_title),
        help = stringResource(R.string.opensubtitles_step_body),
        attribution = { OpenSubtitlesAttribution() },
        code = {},
        rows = {
            Text(stringResource(R.string.opensubtitles_signed_in_as, name.orEmpty()), SetteeType.RowTitle)
            Text(
                stringResource(R.string.opensubtitles_step_signed_in),
                SetteeType.Body,
                color = SetteeColors.QuietText,
                modifier = Modifier.heightIn(min = SIGNED_IN_HEIGHT).padding(top = 12.dp),
            )
        },
        actions = {
            PillButton(
                stringResource(R.string.onboarding_tmdb_carry_on),
                onClick = onDone,
                modifier = Modifier.takesHighlight(),
            )
        },
    )
}

/** The sign-in rows' height (Sign in on this TV and its status line), which this takes the place of. */
private val SIGNED_IN_HEIGHT = 88.dp

/** Whether Setup offers the OpenSubtitles step: a build with Settee's key, on a box not yet signed in. */
fun ServiceLocator.Graph.offersOpenSubtitles(): Boolean =
    openSubtitles.let { it != null && it.account.signedInAs.value == null }
