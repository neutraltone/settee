/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import app.opentv.R
import app.opentv.core.ViewerError
import app.opentv.ui.design.AerialTv
import app.opentv.ui.design.ErrorMessage
import app.opentv.ui.design.SetteeSpace
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private sealed interface Opening {
    data object Waiting : Opening
    data object Slow : Opening
    data object Open : Opening
    data class Failed(val error: ViewerError) : Opening
}

/**
 * Draws [content] only once [open] has opened the database (#334); [open] gives the failure, or null.
 * Every screen reads the database, and a failure there is thrown from a view model's coroutine, which
 * stops the app with no message on every launch, so the viewer is told the way out from here instead,
 * with [alongsideFailure] (the update offer) over it.
 *
 * Opening usually takes a frame or two, which a waiting TV would only flash through, so it shows
 * only once opening has taken long enough to look stuck, as a migration on the Stick can.
 */
@Composable
fun DatabaseGate(
    open: suspend () -> Throwable?,
    describe: (Throwable) -> ViewerError,
    alongsideFailure: @Composable () -> Unit = {},
    content: @Composable () -> Unit,
) {
    val state by produceState<Opening>(Opening.Waiting) {
        val slow = launch {
            delay(SLOW_OPEN_MILLIS)
            value = Opening.Slow
        }
        val failure = open()
        slow.cancel()
        value = if (failure == null) Opening.Open else Opening.Failed(describe(failure))
    }
    when (val opening = state) {
        Opening.Waiting -> Unit
        Opening.Open -> content()
        Opening.Slow -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            AerialTv(stringResource(R.string.progress_still_working))
        }
        is Opening.Failed -> {
            Box(
                Modifier.fillMaxSize().padding(
                    horizontal = SetteeSpace.ScreenSide,
                    vertical = SetteeSpace.ScreenTopBottom,
                ),
                contentAlignment = Alignment.Center,
            ) {
                ErrorMessage(opening.error.sentence, opening.error.detail)
            }
            alongsideFailure()
        }
    }
}

/** Long enough that an ordinary open never shows the waiting TV. */
private const val SLOW_OPEN_MILLIS = 500L
