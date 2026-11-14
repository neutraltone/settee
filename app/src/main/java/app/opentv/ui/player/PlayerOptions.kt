/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv.ui.player

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.stringResource
import app.opentv.R
import app.opentv.core.SleepTimer
import app.opentv.ui.options.ChoicePanel
import app.opentv.ui.options.ItemOption
import app.opentv.ui.options.OptionsPanel
import app.opentv.ui.options.label
import kotlinx.coroutines.delay

/**
 * What Menu or the bar's Options button opens over the video. It's the cards' options panel, a
 * dialog with its own window, so the highlight stays where it was underneath and comes back as it
 * closes. The sleep timer is handled here since both players treat it the same; anything else
 * chosen goes to [onChoose].
 */
@Composable
internal fun PlayerOptions(options: List<ItemOption>, onChoose: (ItemOption) -> Unit, onDismiss: () -> Unit) {
    var choosingSleep by remember { mutableStateOf(false) }
    if (choosingSleep) {
        SleepTimerPanel(onDismiss)
        return
    }
    // Read once as the list opens: it's only up for a moment, and a minute's drift doesn't matter.
    val minutesLeft = remember { SleepTimer.minutesRemaining() }
    val sleepLeft = minutesLeft?.let { stringResource(R.string.player_sleep_left, it) }
    OptionsPanel(
        title = stringResource(R.string.player_options),
        options = options,
        labelOf = { option ->
            val name = option.label()
            if (option == ItemOption.SleepTimer && sleepLeft != null) "$name · $sleepLeft" else name
        },
        onChoose = { option ->
            if (option == ItemOption.SleepTimer) choosingSleep = true else onChoose(option)
        },
        onDismiss = onDismiss,
    )
}

/** The durations Settings offers, and off: null. */
@Composable
internal fun SleepTimerPanel(onDone: () -> Unit) {
    ChoicePanel<Int?>(
        title = stringResource(R.string.settings_sleep_timer),
        choices = listOf(null) + SleepTimer.presets,
        labelOf = { minutes ->
            if (minutes == null) {
                stringResource(R.string.settings_sleep_off)
            } else {
                stringResource(R.string.settings_sleep_minutes, minutes)
            }
        },
        onChoose = { minutes ->
            if (minutes == null) SleepTimer.clear() else SleepTimer.armMinutes(minutes)
            onDone()
        },
        onDismiss = onDone,
    )
}

/**
 * Calls [onRunOut] when the sleep timer's deadline passes while this player is open, and turns the
 * timer off. Re-arming restarts the wait with the new deadline.
 *
 * A deadline that had already passed when the player opened is only cleared: it ran out with no
 * player there to stop (armed from Settings, or armed here and then left), and acting on it now
 * would close the film or channel on its first frame.
 */
@Composable
internal fun SleepTimerEffect(onRunOut: () -> Unit) {
    val deadline by SleepTimer.deadline.collectAsState()
    val runOut by rememberUpdatedState(onRunOut)
    LaunchedEffect(deadline) {
        if (deadline == null) return@LaunchedEffect
        val wait = SleepTimer.millisRemaining()
        if (wait == null) {
            SleepTimer.clear()
            return@LaunchedEffect
        }
        delay(wait)
        SleepTimer.clear()
        runOut()
    }
}
