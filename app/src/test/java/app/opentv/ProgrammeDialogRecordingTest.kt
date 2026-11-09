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

/** The dialog sees a recording on any of a Channel's Qualities, and only offers to record what plays here (#184). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = TV_1080P, application = Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ProgrammeDialogRecordingTest {

    @get:Rule
    val compose = createComposeRule()

    private fun quality(id: Long, name: String, label: String = "") = Channel(
        id = id,
        sourceId = 1,
        streamId = "$id",
        name = name,
        categoryId = null,
        logoUrl = null,
        epgChannelId = null,
        number = null,
        streamUrl = "https://example.com/$id.ts",
        qualityLabel = label,
        // A tag above what every device plays; Robolectric lists no decoders, as a low-end box lists few.
        qualityRank = if (label == "4K") 400 else 0,
    )

    private fun show(row: ChannelsViewModel.Row, activeRecordings: List<Recording> = emptyList()) {
        compose.setTvContent {
            ProgrammeDialog(
                targetRow = row,
                programme = Programme(
                    feedId = 1,
                    epgChannelId = "news",
                    startUtcMillis = NOW - 1_000,
                    endUtcMillis = NOW + 1_000,
                    title = "The News",
                ),
                nowMillis = NOW,
                sources = emptyList(),
                activeRecordings = activeRecordings,
                scope = CoroutineScope(Dispatchers.Main.immediate),
                onWatch = {},
                onPlayCatchup = {},
                onDismiss = {},
            )
        }
    }

    @Test
    fun `it offers to stop a recording on another Quality than the one playing`() {
        val other = quality(4, "News 4K")
        val playing = quality(2, "News HD")
        val recording = Recording(
            id = 1,
            channelId = other.id,
            sourceId = 1,
            channelName = "News",
            title = "The News",
            filePath = "/tmp/1.ts",
            streamUrl = other.streamUrl,
            status = RecordingStatus.RECORDING,
        )

        show(ChannelsViewModel.Row(LineupChannel(playing, listOf(other, playing))), listOf(recording))

        compose.onNodeWithText("■ Stop recording").assertExists()
    }

    @Test
    fun `its Quality choice leaves out what's too big to play here`() {
        val uhd = quality(4, "News 4K", "4K")
        val fhd = quality(3, "News FHD", "FHD")
        val hd = quality(2, "News HD", "HD")

        show(ChannelsViewModel.Row(LineupChannel(fhd, listOf(uhd, fhd, hd))))

        compose.onNodeWithText("FHD").assertExists()
        compose.onNodeWithText("HD").assertExists()
        compose.onNodeWithText("4K").assertDoesNotExist()
    }

    private companion object {
        const val NOW = 1_000_000L
    }
}
