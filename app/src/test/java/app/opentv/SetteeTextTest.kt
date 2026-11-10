/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv

import android.app.Application
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.unit.dp
import app.opentv.ui.design.ScreenFrame
import app.opentv.ui.design.SetteeColors
import app.opentv.ui.design.SetteeType
import app.opentv.ui.design.Text
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = TV_1080P, application = Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class SetteeTextTest {

    @get:Rule
    val compose = createComposeRule()

    private fun showRoles(title: String) = compose.setTvContent {
        ScreenFrame {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(title, style = SetteeType.Display)
                Text("$title: Hero", style = SetteeType.Hero)
                Text("$title: Screen title", style = SetteeType.ScreenTitle)
                Text("$title: Section", style = SetteeType.Section)
                Text("A synopsis, 18 sp, with figures 1 2 3 0 that line up.", style = SetteeType.Synopsis)
                Text("Card title: S2 E10", style = SetteeType.CardTitle)
                Text("Row title: 21:00 to 22:30", style = SetteeType.RowTitle)
                Text("Body and labels, at the 16 sp floor.", style = SetteeType.Body)
                Text("Quiet text: 2 seasons", style = SetteeType.Body, color = SetteeColors.QuietText)
                Text("Accent text: Watching", style = SetteeType.Body, color = SetteeColors.Accent)
            }
        }
    }

    @Test
    fun `every type role draws in Settee's faces`() {
        showRoles("Settee")
        compose.onNodeWithText("Settee: Hero").assertExists()
        captureScreen("ds_text_roles")
    }

    @Test
    @Config(qualifiers = "+ru")
    fun `titles in Russian are drawn in Alegreya Sans Bold`() {
        showRoles("Сериалы")
        compose.onNodeWithText("Сериалы: Hero").assertExists()
        captureScreen("ds_text_roles_ru")
    }
}
