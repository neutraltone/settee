/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv

import android.app.Application
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isFocused
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.test.espresso.Espresso
import app.opentv.data.model.Channel
import app.opentv.data.model.Programme
import app.opentv.data.model.Recording
import app.opentv.data.model.RecordingStatus
import app.opentv.data.model.Source
import app.opentv.data.model.SourceKind
import app.opentv.data.repo.LineupChannel
import app.opentv.ui.ChannelsViewModel
import app.opentv.ui.channels.ProgrammeDetails
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Programme details, the panel OK on a programme in the guide opens (#164): the programme at the
 * top and what can be done with it as rows. It sees a recording on any of a Channel's Qualities,
 * and only offers to record what plays here (#184).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = TV_1080P, application = Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@OptIn(ExperimentalTestApi::class)
class ProgrammeDetailsTest {

    @get:Rule
    val compose = createComposeRule()

    @get:Rule
    val artwork = PlaceholderArtwork()

    private var dismissed = false

    private fun quality(id: Long, name: String, label: String = "", source: Long = 1) = Channel(
        id = id,
        sourceId = source,
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

    private val onNow = Programme(
        feedId = 1,
        epgChannelId = "news",
        startUtcMillis = NOW - 1_000,
        endUtcMillis = NOW + 1_000,
        title = "The News",
        description = "The day's headlines.",
    )

    private fun show(
        row: ChannelsViewModel.Row,
        activeRecordings: List<Recording> = emptyList(),
        sources: List<Source> = emptyList(),
    ) {
        compose.setTvContent {
            ProgrammeDetails(
                targetRow = row,
                programme = onNow,
                nowMillis = NOW,
                sources = sources,
                activeRecordings = activeRecordings,
                scope = CoroutineScope(Dispatchers.Main.immediate),
                onWatch = {},
                onPlayCatchup = {},
                onDismiss = { dismissed = true },
            )
        }
        compose.waitForIdle()
    }

    private fun row(vararg qualities: Channel, primary: Channel = qualities.first()) =
        ChannelsViewModel.Row(LineupChannel(primary, qualities.toList()))

    private fun press(key: Key) {
        compose.onNode(isFocused()).performKeyInput { pressKey(key) }
        compose.waitForIdle()
    }

    @Test
    fun `the programme is at the top, its actions are rows beneath, and there's no Cancel`() {
        show(row(quality(2, "News HD")))

        compose.onNodeWithText("The News").assertExists()
        compose.onNodeWithText("News HD").assertExists()
        compose.onNodeWithText("The day's headlines.").assertExists()
        compose.onNode(hasText("Record now", substring = true) and hasClickAction()).assertIsFocused()
        compose.onNode(hasText("Record whole series") and hasClickAction()).assertExists()
        compose.onNode(hasText("Watch channel") and hasClickAction()).assertExists()
        compose.onNodeWithText("Cancel").assertDoesNotExist()
        captureScreen("programme_details")
    }

    @Test
    fun `Back closes it`() {
        show(row(quality(2, "News HD")))

        Espresso.pressBack()
        compose.waitForIdle()

        assertThat(dismissed).isTrue()
    }

    @Test
    fun `Quality shows the current choice, and OK opens a panel with it ticked`() {
        val fhd = quality(3, "News FHD", "FHD")
        val hd = quality(2, "News HD", "HD")
        show(row(fhd, hd))

        compose.onNode(hasText("Quality") and hasClickAction()).assertExists()
        compose.onNode(hasText("FHD") and hasClickAction()).assertExists()
        compose.onNode(hasText("Quality") and hasClickAction()).performKeyInput { pressKey(Key.DirectionCenter) }
        compose.waitForIdle()

        compose.onNode(hasText("FHD") and hasClickAction()).assertIsSelected()
        compose.onNode(hasText("HD") and hasClickAction()).assertIsNotSelected()
        captureScreen("programme_details_quality")

        press(Key.DirectionDown)
        press(Key.DirectionCenter)

        compose.onNode(hasText("Quality") and hasClickAction()).assertIsFocused()
        compose.onNode(hasText("HD") and hasClickAction()).assertExists()
        compose.onNode(hasText("FHD")).assertDoesNotExist()
    }

    @Test
    fun `Record from shows the current provider, and OK opens a panel with it ticked`() {
        val sources = listOf(
            Source(id = 1, name = "Provider A", kind = SourceKind.XTREAM, url = "https://example.com"),
            Source(id = 2, name = "Provider B", kind = SourceKind.XTREAM, url = "https://example.org"),
        )
        show(row(quality(2, "News HD", "HD"), quality(5, "News HD", "HD", source = 2)), sources = sources)
        compose.waitUntilAtLeastOneExists(hasText("Record from") and hasClickAction(), 5_000)

        compose.onNode(hasText("Provider A") and hasClickAction()).assertExists()
        compose.onNode(hasText("Record from") and hasClickAction()).performKeyInput { pressKey(Key.DirectionCenter) }
        compose.waitForIdle()

        compose.onNode(hasText("Provider A") and hasClickAction()).assertIsSelected()
        compose.onNode(hasText("Provider B") and hasClickAction()).assertIsNotSelected()
        captureScreen("programme_details_record_from")

        press(Key.DirectionDown)
        press(Key.DirectionCenter)

        compose.onNode(hasText("Provider B") and hasClickAction()).assertExists()
        compose.onNode(hasText("Provider A")).assertDoesNotExist()
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

        show(row(other, playing, primary = playing), listOf(recording))

        compose.onNode(hasText("Stop recording", substring = true) and hasClickAction()).assertExists()
    }

    @Test
    fun `its Quality choice leaves out what's too big to play here`() {
        val uhd = quality(4, "News 4K", "4K")
        val fhd = quality(3, "News FHD", "FHD")
        val hd = quality(2, "News HD", "HD")
        show(row(uhd, fhd, hd, primary = fhd))

        compose.onNode(hasText("Quality") and hasClickAction()).performKeyInput { pressKey(Key.DirectionCenter) }
        compose.waitForIdle()

        compose.onNode(hasText("FHD") and hasClickAction()).assertExists()
        compose.onNode(hasText("HD") and hasClickAction()).assertExists()
        compose.onNodeWithText("4K").assertDoesNotExist()
    }

    private companion object {
        const val NOW = 1_000_000L
    }
}
