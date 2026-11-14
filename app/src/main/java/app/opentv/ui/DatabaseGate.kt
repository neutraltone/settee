/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv.ui

import android.database.SQLException
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import app.opentv.core.ViewerError
import app.opentv.ui.design.ErrorMessage
import app.opentv.ui.design.SetteeSpace

private sealed interface Opening {
    data object Waiting : Opening
    data object Open : Opening
    data class Failed(val error: ViewerError) : Opening
}

/**
 * Draws [content] only once [open] has opened the database (#334). Every screen reads it, and a
 * failure there is thrown from a view model's coroutine, which stops the app with no message on every
 * launch. The in-app updater is behind it too, so the viewer has to be told the way out from here.
 *
 * Draws nothing while waiting: opening takes a frame or two, or longer once while a migration runs.
 */
@Composable
fun DatabaseGate(open: suspend () -> Unit, describe: (Throwable) -> ViewerError, content: @Composable () -> Unit) {
    val state by produceState<Opening>(Opening.Waiting) {
        value = try {
            open()
            Opening.Open
        } catch (missingMigration: IllegalStateException) {
            // Room's: no migration from the version on disk, or a schema that doesn't match.
            Opening.Failed(describe(missingMigration))
        } catch (unreadable: SQLException) {
            // SQLite's: a full disk, or a file it can't open.
            Opening.Failed(describe(unreadable))
        }
    }
    when (val opening = state) {
        Opening.Waiting -> Unit
        Opening.Open -> content()
        is Opening.Failed -> Box(
            Modifier.fillMaxSize().padding(horizontal = SetteeSpace.ScreenSide, vertical = SetteeSpace.ScreenTopBottom),
            contentAlignment = Alignment.Center,
        ) {
            ErrorMessage(opening.error.sentence, opening.error.detail)
        }
    }
}
