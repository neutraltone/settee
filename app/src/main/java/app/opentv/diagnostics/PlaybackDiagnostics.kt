/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv.diagnostics

import app.opentv.core.ViewerError

/**
 * What a player tells Diagnostics. Called on Main, from the player's own callbacks, so neither may
 * wait: [DiagnosticsLog] hands its writes to IO.
 */
interface PlaybackDiagnostics {
    /** A film or episode asked for reached its first frame, or failed with [failure] on screen, [millis] later. */
    fun opened(kind: OpenKind, millis: Long, failure: ViewerError?)

    /** A player showed [error]. */
    fun errorShown(error: ViewerError)
}
