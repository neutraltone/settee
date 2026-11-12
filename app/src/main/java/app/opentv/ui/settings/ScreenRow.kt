/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv.ui.settings

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester

/** The open Settings' memory, for rows that open a screen of their own. */
internal val LocalSettingsMemory = staticCompositionLocalOf<SettingsMemory?> { null }

/** A row that opens a screen of its own: [modifier] goes on the row, and [open] is its OK. */
internal class ScreenRow(val modifier: Modifier, val open: () -> Unit)

/**
 * For a row that opens a screen of its own, such as the channel manager. Settings is rebuilt when
 * the viewer comes back, so it remembers which row ([key]) opened the screen, and that row takes the
 * Highlight back.
 */
@Composable
internal fun screenRow(key: String, open: () -> Unit): ScreenRow {
    val memory = LocalSettingsMemory.current
    val requester = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        if (memory?.openedRow != key) return@LaunchedEffect
        withFrameNanos {}
        if (runCatching { requester.requestFocus() }.getOrNull() == true) memory.openedRow = null
    }
    return ScreenRow(Modifier.focusRequester(requester)) {
        memory?.openedRow = key
        open()
    }
}
