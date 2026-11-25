/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv.ui.channels

import androidx.compose.ui.input.key.KeyEvent

/**
 * Pages a day with this, unless [event] is the remote repeating a held key: holding Right runs along
 * the row and stops at the day's end, rather than paging through the days a landing at a time.
 */
internal fun (() -> Unit).unlessHeld(event: KeyEvent) {
    if (event.nativeKeyEvent.repeatCount == 0) this()
}
