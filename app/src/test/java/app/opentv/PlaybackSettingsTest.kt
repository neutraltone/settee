/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv

import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.isToggleable
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import app.opentv.ui.settings.PlaybackSettingsSection
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
class PlaybackSettingsTest {

    @get:Rule
    val compose = createComposeRule()

    private var toggledTo: Boolean? = null

    private fun show(subtitlesOn: Boolean) {
        compose.setTvContent {
            PlaybackSettingsSection(
                subtitlesOn = subtitlesOn,
                onSubtitlesToggle = { toggledTo = it },
                playNext = true,
                onPlayNextToggle = {},
            )
        }
        compose.waitForIdle()
    }

    private val subtitlesSwitch get() = compose.onAllNodes(isToggleable()).onFirst()

    @Test
    fun `the subtitles toggle shows the Profile's choice and says it covers every kind of title`() {
        show(subtitlesOn = true)

        subtitlesSwitch.assertIsOn()
        compose.onNodeWithText("channels, movies and shows", substring = true).assertExists()
        captureScreen("settings_playback")
    }

    @Test
    fun `pressing the subtitles toggle turns them off for the Profile`() {
        show(subtitlesOn = true)

        compose.onNodeWithText("Show subtitles when available").performClick()

        assertThat(toggledTo).isFalse()
    }

    @Test
    fun `a Profile that turned subtitles off sees the toggle off`() {
        show(subtitlesOn = false)

        subtitlesSwitch.assertIsOff()
    }
}
