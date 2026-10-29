/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.core.app.ApplicationProvider
import app.opentv.core.AppSettings
import app.opentv.ui.settings.AppSettingsScreen
import com.google.common.truth.Truth.assertThat
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

// Plain Application: OpenTvApp schedules WorkManager on start, which the screen doesn't need.
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = TV_1080P, application = android.app.Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class AppSettingsScreenTest {

    @get:Rule
    val compose = createComposeRule()

    private val settings = AppSettings.get(ApplicationProvider.getApplicationContext())

    @After
    fun restore() {
        settings.setPlayNextAutomatically(true)
        settings.setLivePreviewOnHome(true)
    }

    @Test
    fun `Play next episode automatically is on until it is switched off`() {
        compose.setTvContent { AppSettingsScreen(onBack = {}) }

        compose.onNodeWithText("Play next episode automatically").performScrollTo()
        captureScreen("app_settings_play_next")
        compose.onNodeWithText("Play next episode automatically").performClick()

        assertThat(settings.playNextAutomatically.value).isFalse()
    }

    @Test
    fun `Live preview on Home is on until it is switched off`() {
        compose.setTvContent { AppSettingsScreen(onBack = {}) }

        compose.onNodeWithText("Live preview on Home").performScrollTo()
        captureScreen("app_settings_live_preview_on_home")
        compose.onNodeWithText("Live preview on Home").performClick()

        assertThat(settings.livePreviewOnHome.value).isFalse()
    }

    @Test
    fun `there is no setting to start the app in the last channel`() {
        compose.setTvContent { AppSettingsScreen(onBack = {}) }

        compose.onNodeWithText("Resume last channel on startup").assertDoesNotExist()
    }
}
