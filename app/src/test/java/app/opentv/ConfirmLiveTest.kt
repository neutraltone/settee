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
import app.opentv.data.model.Recording
import app.opentv.data.model.RecordingStatus
import app.opentv.ui.design.PillButton
import app.opentv.ui.options.ConfirmLiveHost
import app.opentv.ui.options.LocalConfirmLive
import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = TV_1080P, application = android.app.Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ConfirmLiveTest {

    @get:Rule
    val compose = createComposeRule()

    private var started = 0
    private val stopped = mutableListOf<Long>()

    private fun recording(id: Long, title: String) = Recording(
        id = id,
        channelId = id,
        sourceId = 1,
        channelName = "BBC One",
        title = title,
        filePath = "/tmp/$id.ts",
        streamUrl = "http://example.com/live/u/p/$id.ts",
        status = RecordingStatus.RECORDING,
    )

    private fun show(vararg active: Recording) {
        compose.setTvContent {
            ConfirmLiveHost(active.toList(), onStopRecording = { stopped += it.id }) {
                val confirmLive = LocalConfirmLive.current
                PillButton("Play", onClick = { confirmLive { started++ } })
            }
        }
        compose.onNodeWithText("Play").performClick()
    }

    @Test
    fun `with nothing recording, it goes straight to playing`() {
        show()

        assertThat(started).isEqualTo(1)
        compose.onNodeWithText("Recording in progress").assertDoesNotExist()
    }

    @Test
    fun `with a recording running, it asks first and names the recording`() {
        show(recording(1, "The News"))

        assertThat(started).isEqualTo(0)
        compose.onNodeWithText("Recording in progress").assertExists()
        compose.onNodeWithText("The News", substring = true).assertExists()
        captureScreen("confirm_live_recording_running")
    }

    @Test
    fun `it asks in a Side Panel with the highlight on keeping the recording`() {
        show(recording(1, "The News"))

        compose.onNodeWithText("Keep recording").assertIsFocused().assertIsInSidePanel()
    }

    @Test
    fun `back keeps recording and plays nothing`() {
        show(recording(1, "The News"))

        compose.pressBackOnPanel()

        assertThat(stopped).isEmpty()
        assertThat(started).isEqualTo(0)
        compose.onNodeWithText("Recording in progress").assertDoesNotExist()
    }

    @Test
    fun `choosing to watch stops every recording, then plays`() {
        show(recording(1, "The News"), recording(2, "Film"))

        compose.onNodeWithText("Watch live (stop recording)").performClick()

        assertThat(stopped).containsExactly(1L, 2L)
        assertThat(started).isEqualTo(1)
        compose.onNodeWithText("Recording in progress").assertDoesNotExist()
    }

    @Test
    fun `choosing to keep recording plays nothing and stops nothing`() {
        show(recording(1, "The News"))

        compose.onNodeWithText("Keep recording").performClick()

        assertThat(stopped).isEmpty()
        assertThat(started).isEqualTo(0)
        compose.onNodeWithText("Recording in progress").assertDoesNotExist()
    }
}
