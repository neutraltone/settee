/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv.ui.onboarding

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import app.opentv.R
import app.opentv.core.ServiceLocator
import app.opentv.core.ViewerError
import app.opentv.ui.design.ErrorMessage
import app.opentv.ui.design.PillButton
import app.opentv.ui.design.ScreenFrame
import app.opentv.ui.design.SetteeColors
import app.opentv.ui.design.SetteeType
import app.opentv.ui.design.Text
import app.opentv.ui.settings.TmdbAttribution
import app.opentv.ui.settings.TmdbKeyRows
import app.opentv.update.startsFocused

/**
 * Onboarding's optional Add your TMDB Key, after the first provider's form and only while there's no
 * key. Setup's first Catalogue Refresh is already running behind it, so the viewer waits for nothing
 * here. The same rows as Settings › TMDB, plus Skip for now; a key TMDB turns down offers Try again
 * and Skip for now, and never touches the provider. [onDone] goes on to Setup, saved or skipped.
 */
@Composable
fun TmdbStepScreen(graph: ServiceLocator.Graph, onDone: () -> Unit) {
    var turnedDown by remember { mutableStateOf<ViewerError?>(null) }
    ScreenFrame {
        Column(
            Modifier
                .widthIn(max = STEP_WIDTH)
                .verticalScroll(rememberScrollState())
                .padding(vertical = 48.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(stringResource(R.string.onboarding_tmdb_title), SetteeType.ScreenTitle)
            Text(stringResource(R.string.onboarding_tmdb_help), SetteeType.Body, color = SetteeColors.QuietText)
            Spacer(Modifier.height(8.dp))
            TmdbKeyRows(
                keys = graph.tmdbKeys,
                onboarding = true,
                onSaved = onDone,
                onTurnedDown = { turnedDown = it },
                showProblem = false,
                graph = graph,
            )
            val why = turnedDown
            if (why != null) {
                ErrorMessage(why.sentence, why.detail, textAlign = TextAlign.Start)
            }
            Row(
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier.padding(top = 12.dp).testTag(TMDB_STEP_ACTIONS),
            ) {
                if (why != null) {
                    PillButton(stringResource(R.string.common_try_again), onClick = { turnedDown = null })
                }
                PillButton(
                    stringResource(R.string.onboarding_tmdb_skip),
                    onClick = onDone,
                    modifier = Modifier.startsFocused(),
                )
            }
            TmdbAttribution(Modifier.padding(top = 20.dp))
        }
    }
}

private val STEP_WIDTH = 900.dp

/** The step's own Try again and Skip for now, apart from a Try again for the phone's server. */
const val TMDB_STEP_ACTIONS = "tmdb_step_actions"
