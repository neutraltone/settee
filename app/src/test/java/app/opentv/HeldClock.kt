/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv

import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.test.junit4.ComposeContentTestRule

/** Settles what's on screen, then holds the clock so a test can step through an animation frame by frame. */
fun ComposeContentTestRule.holdClock() {
    waitForIdle()
    mainClock.autoAdvance = false
}

/** Makes [change] to state with the clock held, and draws one frame of it. */
fun ComposeContentTestRule.changeWithClockHeld(change: () -> Unit) {
    change()
    // The clock is held, so nothing else tells Compose the state changed.
    Snapshot.sendApplyNotifications()
    mainClock.advanceTimeByFrame()
}

/**
 * Draws the first frames of a window that has just opened with the clock held, such as a Side
 * Panel's, where its Highlight is placed.
 */
fun ComposeContentTestRule.attachNewWindow() {
    // The window is attached by the main looper, which a held clock doesn't run.
    waitForIdle()
    repeat(3) { mainClock.advanceTimeByFrame() }
}
