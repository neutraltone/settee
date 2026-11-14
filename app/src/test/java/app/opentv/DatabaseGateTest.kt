/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv

import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import app.opentv.core.ErrorWording
import app.opentv.ui.DatabaseGate
import app.opentv.ui.design.ERROR_DETAIL_TAG
import app.opentv.ui.design.SetteeType
import app.opentv.ui.design.Text
import app.opentv.update.UpdateChecker
import app.opentv.update.UpdateChecker.Outcome
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * A database Settee can't open said nothing and stopped the app on every launch, so the in-app
 * updater never got the chance to deliver a fix (#334). Now the viewer is told what the way out is,
 * which depends on whether a release that fixes it is out yet.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = TV_1080P, application = android.app.Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class DatabaseGateTest {

    @get:Rule
    val compose = createComposeRule()

    private val wording = ErrorWording(ApplicationProvider.getApplicationContext(), onTelevision = true)
    private val refused = IllegalStateException("A migration from 15 to 14 was required but not found.")
    private val update = Outcome.Available(
        UpdateChecker.Update("1.0.0", "v1.0.0", null, "https://example.com/settee.apk", 1, "https://example.com"),
    )

    private fun show(
        open: suspend () -> Throwable?,
        checkForUpdate: suspend () -> Outcome = { Outcome.UpToDate },
        updateShowing: Boolean = false,
    ) = compose.setTvContent {
        DatabaseGate(
            open = open,
            checkForUpdate = checkForUpdate,
            updateShowing = updateShowing,
            describe = wording::ofDatabase,
            alongsideFailure = { Text("Update panel", SetteeType.Body) },
        ) {
            Text("Home", SetteeType.Body)
        }
    }

    @Test
    fun `the app opens once its database has`() {
        show(open = { null })

        compose.onNodeWithText("Home").assertExists()
        compose.onNodeWithText("Update panel").assertDoesNotExist()
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
    fun `a database that won't open looks for an update that fixes it first`() {
        show(open = { refused }, checkForUpdate = { awaitCancellation() })

        compose.onNodeWithText("Home").assertDoesNotExist()
        compose.onNodeWithText(
            "Settee can't read what it saved on this TV. Checking for an update that fixes it.",
        ).assertExists()
        compose.onNodeWithTag(ERROR_DETAIL_TAG, useUnmergedTree = true).assertTextEquals("IllegalState")
        compose.onNodeWithText("Check again").assertDoesNotExist()
    }

    @Test
    fun `with an update out, it's offered over the message`() {
        show(open = { refused }, checkForUpdate = { update }, updateShowing = true)

        compose.onNodeWithText(
            "Settee can't read what it saved on this TV, and there's an update that fixes it.",
        ).assertExists()
        compose.onNodeWithText("Update panel").assertExists()
        captureScreen("database_unreadable_update")
    }

    @Test
    fun `with no update yet, clearing the data is the way out, and it says what that loses`() {
        show(open = { refused }, checkForUpdate = { Outcome.UpToDate })

        compose.onNodeWithText(
            "Settee can't read what it saved on this TV, and there's no update to fix it yet. Clearing its " +
                "data in this TV's settings for apps starts it afresh, but loses your providers, Favourites " +
                "and watch history.",
        ).assertExists()
        compose.onNodeWithText("Check again").assertIsFocused()
        captureScreen("database_unreadable_no_update")
    }

    @Test
    fun `when it couldn't check, it says to connect and check again, or clear the data`() {
        show(open = { refused }, checkForUpdate = { Outcome.CouldNotCheck("UnknownHost") })

        compose.onNodeWithText(
            "Settee can't read what it saved on this TV, and couldn't check for an update. Connect this TV " +
                "to the internet and check again, or clear its data in this TV's settings for apps, which " +
                "loses your providers, Favourites and watch history.",
        ).assertExists()
        compose.onNodeWithText("Check again").assertIsFocused()
        captureScreen("database_unreadable_offline")
    }

    @Test
    fun `Check again looks for an update again`() {
        val outcomes = ArrayDeque(listOf(Outcome.CouldNotCheck("UnknownHost"), update))
        show(open = { refused }, checkForUpdate = { outcomes.removeFirst() })

        compose.onNodeWithText("Check again").performClick()

        compose.onNodeWithText(
            "Settee can't read what it saved on this TV, and there's an update that fixes it.",
        ).assertExists()
    }

    @Test
    fun `once the update panel is put away, the highlight lands on the message's button`() {
        show(open = { refused }, checkForUpdate = { update }, updateShowing = false)

        compose.onNodeWithText("See the update").assertIsFocused()
    }
}
