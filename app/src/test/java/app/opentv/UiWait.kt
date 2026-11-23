/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv

import android.os.Looper
import androidx.compose.ui.test.junit4.ComposeTestRule
import org.robolectric.Shadows.shadowOf

/**
 * How long a UI test waits for a screen to reach the state it checks. A wait ends as soon as it does,
 * so this only decides how long a test takes to fail. CI's runners share their cores across the whole
 * suite, and 5 s was too short there for a screen that was correct, just slow behind a stall.
 */
const val UI_WAIT_MILLIS = 15_000L

/**
 * Waits up to [UI_WAIT_MILLIS] for [condition], running the main looper before each check. Work off
 * the main thread (a Room read, a connection test) comes back on that looper, and `waitUntil` with a
 * condition that only reads a value never runs it, so the answer would never arrive. A wait for nodes,
 * such as `waitUntilAtLeastOneExists`, needs no help: fetching them waits for idle, which runs it.
 */
fun ComposeTestRule.waitRunningMainLooper(condition: () -> Boolean) = waitUntil(UI_WAIT_MILLIS) {
    shadowOf(Looper.getMainLooper()).idle()
    condition()
}
