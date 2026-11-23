/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv

import androidx.compose.ui.test.junit4.ComposeTestRule

/**
 * How long a UI test waits for a screen to reach the state it checks. A wait ends as soon as it does,
 * so this only decides how long a test takes to fail. CI's runners share their cores across the whole
 * suite, and 5 s was too short there for a screen that was correct, just slow behind a stall.
 */
const val UI_WAIT_MILLIS = 15_000L

/**
 * Waits until [condition] holds, running the main looper before each check. A view model's database
 * reads and network answers come back on the main thread, and under Robolectric its looper runs only
 * when a test idles it. [ComposeTestRule.waitUntil] never does that, it only moves Compose's clock and
 * sleeps. So an answer that arrives after the screen last went idle stays queued, however long the wait.
 */
fun ComposeTestRule.waitUntilMainCaughtUp(condition: () -> Boolean) = waitUntil(UI_WAIT_MILLIS) {
    waitForIdle()
    condition()
}
