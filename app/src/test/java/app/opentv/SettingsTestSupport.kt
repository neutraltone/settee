/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv

import android.view.View
import androidx.compose.ui.test.junit4.ComposeContentTestRule
import androidx.test.espresso.Espresso
import androidx.test.espresso.UiController
import androidx.test.espresso.ViewAction
import androidx.test.espresso.matcher.RootMatchers
import androidx.test.espresso.matcher.ViewMatchers
import app.opentv.data.repo.EpgRepository
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking

/**
 * Waits for what a press saved. A condition that only reads the database never lets the main
 * thread run, and the save resumes there between its steps, so on its own it would never finish:
 * each check here lets it catch up first.
 */
internal fun ComposeContentTestRule.waitUntilSaved(saved: () -> Boolean) {
    val until = System.currentTimeMillis() + 5_000
    while (true) {
        waitForIdle()
        if (saved()) return
        check(System.currentTimeMillis() < until) { "what was saved still isn't there after 5 seconds" }
        Thread.sleep(10)
    }
}

/** The keyboard over an entry screen, which is a window of its own over Settings. */
internal fun ComposeContentTestRule.entryKeyboard(): DeviceKeyboard {
    waitForIdle()
    var root: View? = null
    Espresso.onView(ViewMatchers.isRoot()).inRoot(RootMatchers.isDialog()).perform(
        object : ViewAction {
            override fun getConstraints() = ViewMatchers.isRoot()
            override fun getDescription() = "find the entry screen's window"
            override fun perform(controller: UiController, view: View) {
                root = view
            }
        },
    )
    return DeviceKeyboard(this) { checkNotNull(root) }
}

/** The saved guide at [url], if there is one. */
internal fun EpgRepository.savedFeed(url: String) = runBlocking { observeFeeds().first() }.firstOrNull { it.url == url }
