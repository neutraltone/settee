/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv

import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import app.opentv.ui.options.ItemOption
import app.opentv.ui.options.OptionsPanel
import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

// Plain Application: OpenTvApp schedules WorkManager on start, which a lone composable doesn't need.
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = TV_1080P, application = android.app.Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class OptionsPanelTest {

    @get:Rule
    val compose = createComposeRule()

    private var chosen: ItemOption? = null

    private fun show(vararg options: ItemOption) = compose.setTvContent {
        OptionsPanel(title = "The Show", options = options.toList(), onChoose = { chosen = it }, onDismiss = {})
    }

    @Test
    fun `the panel shows only the options it is given, the first highlighted`() {
        show(ItemOption.MarkWatched, ItemOption.RemoveFromContinueWatching, ItemOption.AddFavourite)

        compose.onNodeWithText("Mark watched").assertIsFocused()
        compose.onNodeWithText("Remove from Continue Watching").assertExists()
        compose.onNodeWithText("Add to Favourites").assertExists()
        compose.onNodeWithText("Mark unwatched").assertDoesNotExist()
        compose.onNodeWithText("Remove from Favourites").assertDoesNotExist()
        captureScreen("options_panel")
    }

    @Test
    fun `choosing an option reports which`() {
        show(ItemOption.MarkUnwatched, ItemOption.RemoveFavourite)

        compose.onNodeWithText("Remove from Favourites").performClick()

        assertThat(chosen).isEqualTo(ItemOption.RemoveFavourite)
    }
}
