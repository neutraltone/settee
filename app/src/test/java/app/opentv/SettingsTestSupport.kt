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
 * Waits for what a press saved. The save resumes on the main thread between its steps, so a condition
 * that only reads the database would never let it finish without [waitRunningMainLooper].
 */
internal fun ComposeContentTestRule.waitUntilSaved(saved: () -> Boolean) = waitRunningMainLooper(saved)

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
