/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv

import android.view.KeyEvent
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import app.opentv.core.RecordingSignals
import app.opentv.ui.recordings.RecordingSwitchWarning
import com.google.common.truth.Truth.assertThat
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The warning that the screen is about to switch to a recording (#437), over a stand-in for the live
 * player that, like the real one, takes Up and Down to change channel before focus search sees them.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = TV_1080P, application = android.app.Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class RecordingSwitchWarningTest {

    @get:Rule
    val compose = createComposeRule()

    private var zaps = 0

    @Before
    @After
    fun forgetSignals() = RecordingSignals.reset()

    private fun showOverPlayer() {
        compose.setTvContent {
            val player = remember { FocusRequester() }
            Box(
                Modifier
                    .fillMaxSize()
                    .testTag(PLAYER)
                    .onPreviewKeyEvent {
                        val zap = it.key == Key.DirectionUp || it.key == Key.DirectionDown
                        if (zap && it.type == KeyEventType.KeyDown) zaps++
                        zap
                    }
                    .focusRequester(player)
                    .focusable(),
            )
            LaunchedEffect(Unit) { player.requestFocus() }
            RecordingSwitchWarning()
        }
        compose.onNodeWithTag(PLAYER).assertIsFocused()
    }

    private fun warn() {
        warnOfRecording()
        compose.waitForIdle()
    }

    @Test
    fun `the warning opens as a Side Panel with the highlight on Keep watching`() {
        showOverPlayer()

        warn()

        compose.onNodeWithText("Recording soon: The News").assertIsInSidePanel()
        compose.onNodeWithText("Starts on Delta Four in 30s. This screen will switch to it.").assertExists()
        compose.onNodeWithText("Keep watching").assertIsFocused().assertIsInSidePanel()
        captureScreen("recording_switch_warning")
    }

    @Test
    fun `a warning for another recording while one is open starts on its own Keep watching`() {
        showOverPlayer()
        warn()

        warnOfRecording(recordingId = WARNED_BOOKING + 1, title = "The Film")
        compose.waitForIdle()

        compose.onNodeWithText("Recording soon: The Film").assertExists()
        compose.onNodeWithText("Keep watching").assertIsFocused()
        compose.pressKeyOnPanel(KeyEvent.KEYCODE_DPAD_CENTER)
        assertThat(switchesWhenRecordingStarts(WARNED_BOOKING + 1)).isFalse()
    }

    @Test
    fun `OK on Keep watching closes the warning, gives the highlight back, and the booking doesn't switch`() {
        showOverPlayer()
        warn()

        compose.pressKeyOnPanel(KeyEvent.KEYCODE_DPAD_CENTER)

        assertThat(RecordingSignals.imminent.value).isNull()
        compose.onNodeWithText("Keep watching").assertDoesNotExist()
        compose.onNodeWithTag(PLAYER).assertIsFocused()
        assertThat(switchesWhenRecordingStarts()).isFalse()
    }

    @Test
    fun `while the warning is open, up and down stay in it and don't change channel`() {
        showOverPlayer()
        warn()

        compose.pressKeyOnPanel(KeyEvent.KEYCODE_DPAD_UP)
        compose.pressKeyOnPanel(KeyEvent.KEYCODE_DPAD_DOWN)

        assertThat(zaps).isEqualTo(0)
        compose.onNodeWithText("Keep watching").assertIsFocused()
        assertThat(RecordingSignals.imminent.value).isNotNull()
    }

    @Test
    fun `Back closes the warning, and the screen still switches when the recording starts`() {
        showOverPlayer()
        warn()

        compose.pressBackOnPanel()

        assertThat(RecordingSignals.imminent.value).isNull()
        compose.onNodeWithText("Keep watching").assertDoesNotExist()
        compose.onNodeWithTag(PLAYER).assertIsFocused()
        assertThat(switchesWhenRecordingStarts()).isTrue()
    }

    @Test
    fun `when the warning clears by itself, it closes and the highlight goes back to where it was`() {
        showOverPlayer()
        warn()

        compose.runOnIdle { RecordingSignals.clearImminent() }
        compose.waitForIdle()

        compose.onNodeWithText("Keep watching").assertDoesNotExist()
        compose.onNodeWithTag(PLAYER).assertIsFocused()
    }

    private companion object {
        const val PLAYER = "player"
    }
}
