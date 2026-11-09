/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv

import android.app.Application
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import app.opentv.data.model.Channel
import app.opentv.data.model.Programme
import app.opentv.data.model.Recording
import app.opentv.data.model.RecordingStatus
import app.opentv.data.repo.LineupChannel
import app.opentv.ui.ChannelsViewModel
import app.opentv.ui.channels.ProgrammeDialog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** A Channel records its best Quality while it plays another, and the dialog has to see that recording (#184). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = TV_1080P, application = Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ProgrammeDialogRecordingTest {

    @get:Rule
    val compose = createComposeRule()

    private fun quality(id: Long, name: String) = Channel(
        id = id,
        sourceId = 1,
        streamId = "$id",
        name = name,
        categoryId = null,
        logoUrl = null,
        epgChannelId = null,
        number = null,
        streamUrl = "https://example.com/$id.ts",
    )

    @Test
    fun `it offers to stop a recording of the best Quality, though another Quality plays`() {
        val best = quality(4, "News 4K")
        val playing = quality(2, "News HD")
        val now = 1_000_000L
        val recording = Recording(
            id = 1,
            channelId = best.id,
            sourceId = 1,
            channelName = "News",
            title = "The News",
            filePath = "/tmp/1.ts",
            streamUrl = best.streamUrl,
            status = RecordingStatus.RECORDING,
        )

        compose.setTvContent {
            ProgrammeDialog(
                targetRow = ChannelsViewModel.Row(LineupChannel(playing, listOf(best, playing))),
                programme = Programme(
                    feedId = 1,
                    epgChannelId = "news",
                    startUtcMillis = now - 1_000,
                    endUtcMillis = now + 1_000,
                    title = "The News",
                ),
                nowMillis = now,
                sources = emptyList(),
                activeRecordings = listOf(recording),
                scope = CoroutineScope(Dispatchers.Main.immediate),
                onWatch = {},
                onPlayCatchup = {},
                onDismiss = {},
            )
        }

        compose.onNodeWithText("■ Stop recording").assertExists()
    }
}
