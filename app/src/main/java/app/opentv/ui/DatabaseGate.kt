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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.res.stringResource
import app.opentv.R
import app.opentv.core.UpdateSearch
import app.opentv.core.ViewerError
import app.opentv.ui.design.ActionButton
import app.opentv.ui.design.AerialTv
import app.opentv.ui.design.AerialTvSize
import app.opentv.ui.design.Message
import app.opentv.ui.design.SetteeSpace
import app.opentv.update.UpdateChecker.Outcome
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private sealed interface Opening {
    data object Waiting : Opening
    data object Slow : Opening
    data object Open : Opening
    data class Failed(val error: Throwable) : Opening
}

/**
 * Draws [content] only once [open] has opened the database (#334); [open] gives the failure, or null.
 * Every screen reads the database, and a failure there is thrown from a view model's coroutine, which
 * stops the app with no message on every launch, so the viewer is told the way out from here instead.
 *
 * The way out is a release that fixes it, so a failure looks for one straight away with
 * [checkForUpdate], and again on Check again. [alongsideFailure] draws the update panel over the
 * message, and [updateShowing] says when it's up, so the message's button doesn't take the highlight
 * from it.
 *
 * Opening usually takes a frame or two, which a waiting TV would only flash through, so it shows
 * only once opening has taken long enough to look stuck, as a migration on the Stick can.
 */
@Composable
fun DatabaseGate(
    open: suspend () -> Throwable?,
    checkForUpdate: suspend () -> Outcome,
    updateShowing: Boolean,
    describe: (Throwable, UpdateSearch) -> ViewerError,
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
        value = if (failure == null) Opening.Open else Opening.Failed(failure)
    }
    when (val opening = state) {
        Opening.Waiting -> Unit
        Opening.Open -> content()
        Opening.Slow -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            AerialTv(stringResource(R.string.progress_still_working))
        }
        is Opening.Failed -> {
            CantOpen(opening.error, checkForUpdate, updateShowing, describe)
            alongsideFailure()
        }
    }
}

@Composable
private fun CantOpen(
    error: Throwable,
    checkForUpdate: suspend () -> Outcome,
    updateShowing: Boolean,
    describe: (Throwable, UpdateSearch) -> ViewerError,
) {
    var search by remember { mutableStateOf(UpdateSearch.CHECKING) }
    var checks by remember { mutableIntStateOf(0) }
    LaunchedEffect(checks) {
        search = UpdateSearch.CHECKING
        search = when (checkForUpdate()) {
            is Outcome.Available -> UpdateSearch.FOUND
            Outcome.UpToDate -> UpdateSearch.NONE
            is Outcome.CouldNotCheck -> UpdateSearch.COULD_NOT_CHECK
        }
    }
    val button = remember { FocusRequester() }
    LaunchedEffect(search, updateShowing) {
        if (search != UpdateSearch.CHECKING && !updateShowing) button.requestFocus()
    }
    val said = describe(error, search)
    Message(
        said.sentence,
        Modifier.padding(horizontal = SetteeSpace.ScreenSide, vertical = SetteeSpace.ScreenTopBottom),
        detail = said.detail,
    ) {
        if (search == UpdateSearch.CHECKING) {
            AerialTv(stringResource(R.string.progress_still_working), size = AerialTvSize.Small)
        } else {
            ActionButton(
                stringResource(
                    if (search ==
                        UpdateSearch.FOUND
                    ) {
                        R.string.database_see_update
                    } else {
                        R.string.database_check_again
                    },
                ),
                onClick = { checks++ },
                modifier = Modifier.focusRequester(button),
            )
        }
    }
}

/** Long enough that an ordinary open never shows the waiting TV. */
private const val SLOW_OPEN_MILLIS = 500L
