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
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.opentv.R
import app.opentv.core.ServiceLocator
import app.opentv.data.repo.TmdbKeys
import app.opentv.ui.design.PillButton
import app.opentv.ui.design.ScreenFrame
import app.opentv.ui.design.SetteeColors
import app.opentv.ui.design.SetteeType
import app.opentv.ui.design.Text
import app.opentv.ui.settings.TmdbAttribution
import app.opentv.ui.settings.TmdbKeyRows
import app.opentv.ui.settings.rememberTmdbKeyEntry
import app.opentv.update.startsFocused

/**
 * Onboarding's optional Add your TMDB Key, after the first provider's form and only while there's no
 * key. Setup's first Catalogue Refresh is already running behind it, so the viewer waits for nothing
 * here. The same rows as Settings › TMDB, plus Skip for now; a key TMDB turns down offers Try again
 * beside it, and never touches the provider. A key TMDB accepts is said to be saved, and the step waits
 * for Carry on rather than moving on by itself, which left nothing on screen long enough to read.
 * [onDone] goes on to Setup, saved or skipped.
 */
@Composable
fun TmdbStepScreen(graph: ServiceLocator.Graph, onDone: () -> Unit) {
    val entry = rememberTmdbKeyEntry(graph.tmdbKeys, graph)
    var saved by remember { mutableStateOf(false) }
    ScreenFrame {
        Column(
            Modifier
                .widthIn(max = STEP_WIDTH)
                .verticalScroll(rememberScrollState())
                .padding(vertical = 48.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(stringResource(R.string.onboarding_tmdb_title), SetteeType.ScreenTitle)
            if (saved) {
                KeySaved(graph, onDone)
            } else {
                Text(stringResource(R.string.onboarding_tmdb_help), SetteeType.Body, color = SetteeColors.QuietText)
                Spacer(Modifier.height(8.dp))
                TmdbKeyRows(entry, onboarding = true, graph = graph, onSaved = { saved = true })
                // Skip for now first, so it stays put when Try again joins it.
                StepActions {
                    PillButton(
                        stringResource(R.string.onboarding_tmdb_skip),
                        onClick = onDone,
                        modifier = Modifier.startsFocused(),
                    )
                    if (entry.problem != null) {
                        PillButton(stringResource(R.string.common_try_again), onClick = entry::clearProblem)
                    }
                }
            }
            TmdbAttribution(Modifier.padding(top = 20.dp))
        }
    }
}

/** The key TMDB accepted, by its last four characters, what it does now, and Carry on. */
@Composable
private fun KeySaved(graph: ServiceLocator.Graph, onDone: () -> Unit) {
    val key by graph.settings.tmdbApiKey.collectAsState()
    Text(stringResource(R.string.settings_row_tmdb_set), SetteeType.Section)
    Text(stringResource(R.string.settings_tmdb_ending, TmdbKeys.endingOf(key).orEmpty()), SetteeType.Body)
    Text(stringResource(R.string.onboarding_tmdb_saved), SetteeType.Body, color = SetteeColors.QuietText)
    StepActions {
        PillButton(
            stringResource(R.string.onboarding_tmdb_carry_on),
            onClick = onDone,
            modifier = Modifier.startsFocused(),
        )
    }
}

@Composable
private fun StepActions(buttons: @Composable () -> Unit) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        modifier = Modifier.padding(top = 12.dp).testTag(TMDB_STEP_ACTIONS),
    ) { buttons() }
}

private val STEP_WIDTH = 900.dp

/** The step's own buttons, apart from a Try again for the phone's server. */
const val TMDB_STEP_ACTIONS = "tmdb_step_actions"
