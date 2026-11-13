/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv.ui.onboarding

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
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
import app.opentv.ui.settings.KeySavedCard
import app.opentv.ui.settings.TmdbAttribution
import app.opentv.ui.settings.TmdbKeyRows
import app.opentv.ui.settings.rememberTmdbKeyEntry

/**
 * Onboarding's optional Add your TMDB Key, after the first provider's form and only while there's no
 * key. Setup's first Catalogue Refresh is already running behind it, so the viewer waits for nothing
 * here. The same rows as Settings › TMDB, plus Skip for now (on the phone too); a key TMDB turns down
 * offers Try again beside it, and never touches the provider. A key TMDB accepts is said to be saved,
 * and the step waits for Carry on rather than moving on by itself, which left nothing on screen long
 * enough to read. [onDone] goes on to Setup, saved or skipped.
 *
 * Laid out in two columns, the rows on the left and the QR code in a card on the right, so it fits a
 * TV screen whole: stacked, it came to half as tall again as a Fire TV Stick's screen, and keeping the
 * Highlight in view scrolled the title off the top. The title, the card's place and the attribution
 * stay where they are when the key is saved.
 */
@Composable
fun TmdbStepScreen(graph: ServiceLocator.Graph, onDone: () -> Unit) {
    val entry = rememberTmdbKeyEntry(graph.tmdbKeys, graph)
    var saved by remember { mutableStateOf(false) }
    if (saved) {
        val key by graph.settings.tmdbApiKey.collectAsState()
        StepFrame(
            left = {
                Text(stringResource(R.string.onboarding_tmdb_saved), SetteeType.Body, color = SetteeColors.QuietText)
                StepActions {
                    PillButton(
                        stringResource(R.string.onboarding_tmdb_carry_on),
                        onClick = onDone,
                        modifier = Modifier.takesHighlight(),
                    )
                }
            },
            right = { KeySavedCard(TmdbKeys.endingOf(key).orEmpty()) },
        )
        return
    }
    TmdbKeyRows(entry, onboarding = true, graph = graph, onSaved = { saved = true }, onSkipped = onDone) { parts ->
        StepFrame(
            left = {
                Text(stringResource(R.string.onboarding_tmdb_help), SetteeType.Body, color = SetteeColors.QuietText)
                parts.typeIt()
                parts.status()
                // Skip for now first, so it stays put when Try again joins it.
                StepActions {
                    PillButton(
                        stringResource(R.string.onboarding_tmdb_skip),
                        onClick = onDone,
                        modifier = Modifier.takesHighlight(),
                    )
                    if (entry.problem != null) {
                        PillButton(stringResource(R.string.common_try_again), onClick = entry::clearProblem)
                    }
                }
            },
            right = { parts.code(true) },
        )
    }
}

/** The step's two columns: the title, [left] and the attribution, beside [right]. */
@Composable
private fun StepFrame(left: @Composable () -> Unit, right: @Composable () -> Unit) {
    ScreenFrame {
        Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(COLUMN_GAP)) {
            Column(Modifier.weight(1f).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(stringResource(R.string.onboarding_tmdb_title), SetteeType.ScreenTitle)
                left()
                Spacer(Modifier.weight(1f))
                TmdbAttribution()
            }
            Box(Modifier.fillMaxHeight(), contentAlignment = Alignment.Center) { right() }
        }
    }
}

@Composable
private fun StepActions(buttons: @Composable () -> Unit) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        modifier = Modifier.padding(top = 4.dp).testTag(TMDB_STEP_ACTIONS),
    ) { buttons() }
}

/**
 * Takes the Highlight when it appears, asking again each frame until it has it, for a few frames. Once
 * isn't enough here: a key saved from the phone swaps the rows away from under the Highlight, and the
 * first ask, a frame later, was refused on a Fire TV Stick, leaving nothing highlighted.
 */
@Composable
private fun Modifier.takesHighlight(): Modifier {
    val focus = remember { FocusRequester() }
    var highlighted by remember { mutableStateOf(false) }
    LaunchedEffect(focus) {
        repeat(FOCUS_TRIES) {
            withFrameNanos {}
            if (highlighted) return@LaunchedEffect
            runCatching { focus.requestFocus() }
        }
    }
    return focusRequester(focus).onFocusChanged { highlighted = it.hasFocus }
}

private const val FOCUS_TRIES = 10
private val COLUMN_GAP = 40.dp

/** The step's own buttons, apart from a Try again for the phone's server. */
const val TMDB_STEP_ACTIONS = "tmdb_step_actions"
