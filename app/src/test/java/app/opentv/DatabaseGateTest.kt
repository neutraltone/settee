/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv

import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.test.core.app.ApplicationProvider
import app.opentv.core.ErrorWording
import app.opentv.ui.DatabaseGate
import app.opentv.ui.design.ERROR_DETAIL_TAG
import app.opentv.ui.design.Text
import kotlinx.coroutines.delay
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * A database Settee can't open said nothing and stopped the app on every launch, so the in-app
 * updater never got the chance to deliver a fix (#334). Now the viewer is told how to get out.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = TV_1080P, application = android.app.Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class DatabaseGateTest {

    @get:Rule
    val compose = createComposeRule()

    private val wording = ErrorWording(ApplicationProvider.getApplicationContext(), onTelevision = true)

    private fun show(open: suspend () -> Throwable?) = compose.setTvContent {
        DatabaseGate(open = open, describe = wording::ofDatabase, alongsideFailure = { Text("Update offered") }) {
            Text("Home")
        }
    }

    @Test
    fun `the app opens once its database has`() {
        show(open = { null })

        compose.onNodeWithText("Home").assertExists()
        compose.onNodeWithText("Update offered").assertDoesNotExist()
    }

    @Test
    fun `a slow open, such as a migration on the Stick, shows it's still working rather than a blank screen`() {
        compose.mainClock.autoAdvance = false
        show(open = {
            delay(5_000)
            null
        })

        compose.mainClock.advanceTimeBy(1_000)

        compose.onNodeWithContentDescription("Still working").assertExists()
        compose.onNodeWithText("Home").assertDoesNotExist()
    }

    @Test
    fun `a quick open doesn't flash the waiting TV first`() {
        compose.mainClock.autoAdvance = false
        show(open = {
            delay(100)
            null
        })

        compose.mainClock.advanceTimeBy(50)

        compose.onNodeWithContentDescription("Still working").assertDoesNotExist()
    }

    @Test
    fun `a database that won't open says how to get out, on any TV, and offers the update`() {
        show(open = { IllegalStateException("A migration from 15 to 14 was required but not found.") })

        compose.onNodeWithText("Home").assertDoesNotExist()
        compose.onNodeWithText(
            "Settee can't read what it saved on this TV. Update it with Downloader code 6405610, " +
                "or clear its data in this TV's settings for apps.",
        ).assertExists()
        compose.onNodeWithTag(ERROR_DETAIL_TAG, useUnmergedTree = true).assertTextEquals("IllegalState")
        compose.onNodeWithText("Update offered").assertExists()
        captureScreen("database_unreadable")
    }
}
