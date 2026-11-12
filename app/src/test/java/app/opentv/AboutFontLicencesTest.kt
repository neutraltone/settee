/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv

import android.app.Application
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.requestFocus
import app.opentv.ui.settings.AboutScreen
import app.opentv.update.UpdateChecker
import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** The fonts are OFL, not GPL, so their licences have to be readable in the app (docs/LOOK.md, Licences). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = TV_1080P, application = Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class AboutFontLicencesTest {

    @get:Rule
    val compose = createComposeRule()

    @Test
    fun `About opens both font licences from its Fonts row`() {
        compose.setTvContent { AboutScreen(onBack = {}) }
        captureScreen("about_initial")

        val row = compose.onNodeWithText("Young Serif and Alegreya Sans", substring = true)
        row.performScrollTo().requestFocus()
        row.performKeyInput { pressKey(Key.DirectionCenter) }

        compose.onNode(isDialog()).assertExists()
        compose.onNode(hasText("Young Serif 3.003")).assertExists()
        compose.onNode(hasText("Alegreya Sans 2.004")).assertExists()
        assertThat(
            compose.onAllNodes(hasText("SIL OPEN FONT LICENSE", substring = true)).fetchSemanticsNodes(),
        ).hasSize(2)
        captureScreen("about_font_licences")
    }

    @Test
    fun `a check that couldn't reach a release says so with its detail line, rather than claiming you're up to date`() {
        compose.setTvContent {
            AboutScreen(onBack = {}, checkForUpdate = { UpdateChecker.Outcome.CouldNotCheck("HTTP 503") })
        }

        val button = compose.onNodeWithText("Check for updates")
        button.performScrollTo().requestFocus()
        button.performKeyInput { pressKey(Key.DirectionCenter) }

        compose.onNodeWithText("Couldn't check for updates. Try again later.").assertExists()
        compose.onNodeWithText("HTTP 503").assertExists()
        compose.onNode(hasText("You're on the latest version.")).assertDoesNotExist()
        captureScreen("about_check_failed")
    }
}
