/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import app.opentv.data.repo.MediaKey
import app.opentv.ui.ContinueWatchingShelf
import app.opentv.ui.ResumeItem
import app.opentv.ui.design.ScreenFrame
import app.opentv.ui.options.LeaveDirectly
import app.opentv.ui.options.LeaveScreen
import app.opentv.ui.options.LocalOpenOptions
import app.opentv.ui.options.OptionsSubject

/**
 * The Continue Watching row Home, Movies and Shows share, on a screen of its own. With [onOptions],
 * a card's options come to it; without, they go to whatever options host is around it.
 */
@Composable
internal fun ContinueWatching(
    items: List<ResumeItem>,
    onResume: (MediaKey) -> Unit,
    onOptions: ((ResumeItem) -> Unit)? = null,
    onLeave: (ResumeItem) -> LeaveScreen = { LeaveDirectly },
) {
    val row = @Composable {
        ScreenFrame {
            ContinueWatchingShelf(items, large = false, focus = null, onResume = onResume, leave = onLeave)
        }
    }
    if (onOptions == null) {
        row()
    } else {
        val open: (OptionsSubject, LeaveScreen) -> Unit = { subject, _ ->
            (subject as? OptionsSubject.OfContinueWatching)?.let { onOptions(it.item) }
        }
        CompositionLocalProvider(LocalOpenOptions provides open) { row() }
    }
}
