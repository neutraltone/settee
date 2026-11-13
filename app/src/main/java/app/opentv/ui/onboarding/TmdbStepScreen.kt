/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv.ui.onboarding

import android.util.Log
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
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
import androidx.compose.ui.input.InputMode
import androidx.compose.ui.platform.LocalInputModeManager
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.unit.dp
import app.opentv.R
import app.opentv.core.ServiceLocator
import app.opentv.data.repo.TmdbKeys
import app.opentv.ui.design.PillButton
import app.opentv.ui.design.ScreenFrame
import app.opentv.ui.design.SetteeColors
import app.opentv.ui.design.SetteeType
import app.opentv.ui.design.Text
import app.opentv.ui.settings.QR_PANEL_HEIGHT
import app.opentv.ui.settings.QR_PANEL_WIDTH
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
 * Laid out as the phone pairing screen before it is, the QR code on the left and the words on the
 * right, so coming on from one to the other nothing jumps; and it fits a TV screen whole, where
 * stacked it came to half as tall again as a Fire TV Stick's and scrolled the title off the top. Every
 * piece keeps its place and size when the key is saved: the key, masked but for its end, fills the
 * row Type it instead was, what happens now takes the status line, Carry on takes Skip for now's place,
 * and the QR code's space stays, empty, now its server has stopped.
 */
@Composable
fun TmdbStepScreen(graph: ServiceLocator.Graph, onDone: () -> Unit) {
    val entry = rememberTmdbKeyEntry(graph.tmdbKeys, graph)
    var saved by remember { mutableStateOf(false) }
    if (saved) {
        val key by graph.settings.tmdbApiKey.collectAsState()
        StepLayout(
            code = {},
            rows = {
                SavedKeyField(TmdbKeys.endingOf(key).orEmpty())
                Text(
                    stringResource(R.string.onboarding_tmdb_saved),
                    SetteeType.Body,
                    color = SetteeColors.QuietText,
                    modifier = Modifier.heightIn(min = STATUS_HEIGHT).padding(top = 12.dp),
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
        return
    }
    TmdbKeyRows(entry, onboarding = true, graph = graph, onSaved = { saved = true }, onSkipped = onDone) { parts ->
        StepLayout(
            code = { parts.code(true) },
            rows = {
                parts.typeIt()
                parts.status()
            },
            actions = {
                // Skip for now first, so it stays put when Try again joins it.
                PillButton(
                    stringResource(R.string.onboarding_tmdb_skip),
                    onClick = onDone,
                    modifier = Modifier.takesHighlight(),
                )
                if (entry.problem != null) {
                    PillButton(stringResource(R.string.common_try_again), onClick = entry::clearProblem)
                }
            },
        )
    }
}

/**
 * The phone pairing screen's layout: centred, [code] in a fixed space on the left, and on the right the
 * title, the help, [rows], [actions] and TMDB's attribution.
 */
@Composable
private fun StepLayout(code: @Composable () -> Unit, rows: @Composable () -> Unit, actions: @Composable () -> Unit) {
    ScreenFrame {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(48.dp)) {
                Box(Modifier.size(QR_PANEL_WIDTH, QR_PANEL_HEIGHT)) { code() }
                Column(Modifier.widthIn(max = WORDS_WIDTH)) {
                    Text(stringResource(R.string.onboarding_tmdb_title), SetteeType.ScreenTitle)
                    Spacer(Modifier.height(12.dp))
                    Text(
                        stringResource(R.string.onboarding_tmdb_help),
                        SetteeType.Synopsis,
                        color = SetteeColors.QuietText,
                    )
                    Spacer(Modifier.height(20.dp))
                    rows()
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        modifier = Modifier.padding(top = 4.dp).testTag(TMDB_STEP_ACTIONS),
                    ) { actions() }
                    Spacer(Modifier.height(24.dp))
                    TmdbAttribution()
                }
            }
        }
    }
}

/**
 * The saved key in Type it instead's place and size, as a field that can't be changed here: masked,
 * but for the last four characters to recognise it by. Read out as what it is rather than dots.
 */
@Composable
private fun SavedKeyField(ending: String) {
    val description = stringResource(R.string.tmdb_saved_key_desc, ending)
    Box(
        Modifier
            .fillMaxWidth()
            .heightIn(min = KEY_ROW_HEIGHT)
            .clearAndSetSemantics { contentDescription = description }
            .padding(horizontal = KEY_ROW_SIDE),
        contentAlignment = Alignment.CenterStart,
    ) {
        Text(MASK.repeat(MASKED) + ending, SetteeType.RowTitle, color = SetteeColors.QuietText, maxLines = 1)
    }
}

/**
 * Takes the Highlight when it appears, and keeps asking, a frame at a time while this window has the
 * system's focus, for up to [FOCUS_PATIENCE_MILLIS]. Asking once, a frame later, wasn't enough: on a
 * Fire TV Stick, coming on from the phone, nothing on the step was highlighted. A clickable can't take
 * focus while Compose thinks input is by touch, which a TV's remote never is, so that's put right
 * first. Each try is logged ([TAG], at info, as the Stick drops debug logs) until it's understood.
 */
@Composable
private fun Modifier.takesHighlight(): Modifier {
    val focus = remember { FocusRequester() }
    var highlighted by remember { mutableStateOf(false) }
    val windowFocused = LocalWindowInfo.current.isWindowFocused
    val inputMode = LocalInputModeManager.current
    val view = LocalView.current
    LaunchedEffect(focus, windowFocused) {
        Log.i(TAG, "Asking: window ${if (windowFocused) "focused" else "not focused"}, ${inputMode.inputMode}")
        if (!windowFocused) return@LaunchedEffect
        if (inputMode.inputMode == InputMode.Touch) {
            Log.i(TAG, "Keyboard mode asked for: ${inputMode.requestInputMode(InputMode.Keyboard)}")
        }
        val until = System.currentTimeMillis() + FOCUS_PATIENCE_MILLIS
        var tries = 0
        while (!highlighted && System.currentTimeMillis() < until) {
            withFrameNanos {}
            tries++
            val asked = runCatching { focus.requestFocus() }
            asked.exceptionOrNull()?.let { Log.i(TAG, "Try $tries refused: ${it.javaClass.simpleName}") }
        }
        Log.i(
            TAG,
            "${if (highlighted) "Highlighted" else "Never highlighted"} after $tries tries; " +
                "view focused ${view.hasFocus()}, touch mode ${view.isInTouchMode}, ${inputMode.inputMode}",
        )
    }
    return focusRequester(focus).onFocusChanged { highlighted = it.hasFocus }
}

private const val TAG = "TmdbStep"
private const val FOCUS_PATIENCE_MILLIS = 2_000L

/** As wide as the pairing screen's words. */
private val WORDS_WIDTH = 520.dp

/** [app.opentv.ui.design.ListRow]'s height and side inset, which the saved key takes the place of. */
private val KEY_ROW_HEIGHT = 52.dp
private val KEY_ROW_SIDE = 10.dp

/** The status line's reserved height (TmdbPane's), which what happens now takes the place of. */
private val STATUS_HEIGHT = 88.dp

private const val MASK = "•"
private const val MASKED = 24

/** The step's own buttons, apart from a Try again for the phone's server. */
const val TMDB_STEP_ACTIONS = "tmdb_step_actions"
