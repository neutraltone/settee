/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv

import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.test.core.app.ApplicationProvider
import app.opentv.core.ErrorWording
import app.opentv.ui.DatabaseGate
import app.opentv.ui.design.ERROR_DETAIL_TAG
import app.opentv.ui.design.Text
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

    private fun show(open: suspend () -> Unit) = compose.setTvContent {
        DatabaseGate(open = open, describe = wording::ofDatabase) { Text("Home") }
    }

    @Test
    fun `the app opens once its database has`() {
        show(open = {})

        compose.onNodeWithText("Home").assertExists()
    }

    @Test
    fun `a database that won't open says how to get out instead of stopping the app`() {
        show(open = { throw IllegalStateException("A migration from 15 to 14 was required but not found.") })

        compose.onNodeWithText("Home").assertDoesNotExist()
        compose.onNodeWithText(
            "Settee can't read what it saved on this TV. Update it with Downloader code 6405610, " +
                "or clear its data in Settings › Applications.",
        ).assertExists()
        compose.onNodeWithTag(ERROR_DETAIL_TAG, useUnmergedTree = true).assertTextEquals("IllegalState")
        captureScreen("database_unreadable")
    }
}
