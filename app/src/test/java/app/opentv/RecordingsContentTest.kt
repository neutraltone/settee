/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv

import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.test.espresso.Espresso
import app.opentv.core.ViewerError
import app.opentv.data.model.Recording
import app.opentv.data.model.RecordingStatus
import app.opentv.data.model.Reminder
import app.opentv.data.model.SeriesRule
import app.opentv.ui.design.ERROR_DETAIL_TAG
import app.opentv.ui.design.SetteeColors
import app.opentv.ui.options.ItemOption
import app.opentv.ui.recordings.RecordingsContent
import app.opentv.ui.recordings.RecordingsRow
import app.opentv.ui.recordings.recordingsSections
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
@OptIn(ExperimentalTestApi::class)
class RecordingsContentTest {

    @get:Rule
    val compose = createComposeRule()

    @get:Rule
    val artwork = PlaceholderArtwork()

    private val played = mutableListOf<Recording>()
    private val retried = mutableListOf<Recording>()
    private val chosen = mutableListOf<Pair<RecordingsRow, ItemOption>>()

    /** What the screen lists, so a test can change it under the screen as the database would. */
    private var shown by mutableStateOf(emptyList<Recording>())

    /**
     * Shows the screen and enters it the way the rail does, by asking the content for the Highlight
     * without saying which row should have it.
     */
    private fun show(
        recordings: List<Recording> = emptyList(),
        reminders: List<Reminder> = emptyList(),
        seriesRules: List<SeriesRule> = emptyList(),
        profileNames: Map<Long, String> = emptyMap(),
    ) {
        val enter = FocusRequester()
        shown = recordings
        compose.setTvContent {
            Box(Modifier.focusRequester(enter)) {
                RecordingsContent(
                    sections = recordingsSections(shown, reminders, seriesRules, NOW),
                    profileNames = profileNames,
                    onPlay = { played += it },
                    onRetry = { retried += it },
                    onChoose = { row, option -> chosen += row to option },
                    now = { NOW },
                )
            }
        }
        compose.runOnIdle { enter.requestFocus() }
        compose.waitForIdle()
    }

    private fun row(title: String): SemanticsNodeInteraction = compose.onNode(
        hasText(title, substring = true) and hasClickAction(),
    )

    private fun press(key: Key) = compose.onRoot().performKeyInput { pressKey(key) }

    @Test
    fun `the first Highlight is on the first row, and Down moves row by row across the headings`() {
        show(
            recordings = listOf(booking(1), completed(2)),
            seriesRules = listOf(seriesLink(3)),
        )

        row("Booking 1").assertIsFocused()
        captureScreen("recordings_initial")
        press(Key.DirectionDown)
        row("Recorded 2").assertIsFocused()
        press(Key.DirectionDown)
        row("Series 3").assertIsFocused()
    }

    @Test
    fun `OK plays a finished recording and retries a failed one`() {
        val finished = completed(1)
        val failed = completed(2, RecordingStatus.FAILED)
        show(recordings = listOf(finished, failed))

        press(Key.DirectionCenter)
        press(Key.DirectionDown)
        press(Key.DirectionCenter)

        assertThat(played).containsExactly(finished)
        assertThat(retried).containsExactly(failed)
    }

    @Test
    fun `OK on a failed booking whose programme is over opens its options, as there's nothing left to record`() {
        val missed = booking(1).copy(
            status = RecordingStatus.FAILED,
            scheduledStartMillis = NOW - 2 * HOUR,
            scheduledEndMillis = NOW - HOUR,
        )
        show(recordings = listOf(missed))

        press(Key.DirectionCenter)

        compose.waitUntilAtLeastOneExists(hasText("Delete"), TIMEOUT_MILLIS)
        assertThat(retried).isEmpty()
    }

    @Test
    fun `OK on a failed booking still on air retries it`() {
        val failing = booking(1).copy(
            status = RecordingStatus.FAILED,
            scheduledStartMillis = NOW - HOUR,
            scheduledEndMillis = NOW + HOUR,
        )
        show(recordings = listOf(failing))

        press(Key.DirectionCenter)

        assertThat(retried).containsExactly(failing)
    }

    @Test
    fun `a booking that starts while its options are open offers Stop, not Cancel`() {
        show(recordings = listOf(booking(1)))
        press(Key.DirectionCenter)
        compose.waitUntilAtLeastOneExists(hasText("Cancel recording"), TIMEOUT_MILLIS)

        shown = listOf(booking(1).copy(status = RecordingStatus.RECORDING))

        compose.waitUntilAtLeastOneExists(hasText("Stop recording"), TIMEOUT_MILLIS)
        compose.onNode(hasText("Cancel recording")).assertDoesNotExist()
    }

    @Test
    fun `the options close when their row goes while they're open`() {
        show(recordings = listOf(booking(1), completed(2)))
        press(Key.DirectionCenter)
        compose.waitUntilAtLeastOneExists(hasText("Cancel recording"), TIMEOUT_MILLIS)

        shown = listOf(completed(2))

        compose.waitUntilDoesNotExist(hasText("Cancel recording"), TIMEOUT_MILLIS)
    }

    @Test
    fun `deleting a recording moves the Highlight to the one that takes its place`() {
        show(recordings = listOf(completed(1), completed(2), completed(3)))
        press(Key.DirectionDown)

        deleteHighlighted()
        shown = shown.filter { it.id != 2L }
        compose.waitForIdle()

        row("Recorded 3").assertIsFocused()
    }

    @Test
    fun `deleting the last recording moves the Highlight to the one before it`() {
        show(recordings = listOf(completed(1), completed(2)))
        press(Key.DirectionDown)

        deleteHighlighted()
        shown = shown.filter { it.id != 2L }
        compose.waitForIdle()

        row("Recorded 1").assertIsFocused()
    }

    @Test
    fun `going back up a long list brings its first heading back`() {
        show(recordings = (1L..LONG_LIST).map { completed(it) })

        repeat(LONG_LIST.toInt() - 1) { press(Key.DirectionDown) }
        repeat(LONG_LIST.toInt() - 1) { press(Key.DirectionUp) }

        compose.onNode(hasText("Recorded 1") and hasClickAction()).assertIsFocused()
        compose.onNodeWithText("Recorded").assertIsDisplayed()
    }

    @Test
    fun `OK on a booking opens its options panel, where it can be cancelled once it's confirmed`() {
        val booked = booking(1)
        show(recordings = listOf(booked))

        press(Key.DirectionCenter)
        compose.waitUntilAtLeastOneExists(hasText("Cancel recording"), TIMEOUT_MILLIS)
        captureScreen("recordings_options")
        compose.onNode(hasText("Cancel recording") and hasClickAction()).performClick()
        compose.waitUntilAtLeastOneExists(hasText("Keep it"), TIMEOUT_MILLIS)
        assertThat(chosen).isEmpty()
        compose.onNode(hasText("Cancel recording") and hasClickAction()).performClick()

        assertThat(chosen).containsExactly(RecordingsRow.Booking(booked, clashes = false) to ItemOption.CancelRecording)
    }

    @Test
    fun `cancelling a Reminder asks first, and Keep it keeps it`() {
        show(reminders = listOf(reminder(1, autoTune = false)))

        press(Key.DirectionCenter)
        compose.waitUntilAtLeastOneExists(hasText("Cancel reminder"), TIMEOUT_MILLIS)
        compose.onNode(hasText("Cancel reminder") and hasClickAction()).performClick()
        compose.waitUntilAtLeastOneExists(hasText("Keep it"), TIMEOUT_MILLIS)
        compose.waitForIdle()
        compose.onNode(hasText("Keep it") and hasClickAction()).assertIsFocused()
        compose.onNode(hasText("Keep it") and hasClickAction()).performClick()
        compose.waitForIdle()

        compose.onNode(hasText("Keep it")).assertDoesNotExist()
        assertThat(chosen).isEmpty()
    }

    @Test
    fun `confirming cancels the Reminder`() {
        val set = reminder(1, autoTune = false)
        show(reminders = listOf(set))

        press(Key.DirectionCenter)
        compose.waitUntilAtLeastOneExists(hasText("Cancel reminder"), TIMEOUT_MILLIS)
        compose.onNode(hasText("Cancel reminder") and hasClickAction()).performClick()
        compose.waitUntilAtLeastOneExists(hasText("Keep it"), TIMEOUT_MILLIS)
        compose.onNode(hasText("Cancel reminder") and hasClickAction()).performClick()

        assertThat(chosen).containsExactly(RecordingsRow.OfReminder(set) to ItemOption.CancelReminder)
    }

    @Test
    fun `removing a series link asks first, and says its bookings go with it`() {
        show(seriesRules = listOf(seriesLink(1)))

        press(Key.DirectionCenter)
        compose.waitUntilAtLeastOneExists(hasText("Remove series link"), TIMEOUT_MILLIS)
        compose.onNode(hasText("Remove series link") and hasClickAction()).performClick()

        compose.waitUntilAtLeastOneExists(hasText("bookings are cancelled", substring = true), TIMEOUT_MILLIS)
        assertThat(chosen).isEmpty()
    }

    @Test
    fun `stopping a recording doesn't ask, as nothing is lost`() {
        val recording = completed(1, RecordingStatus.RECORDING)
        show(recordings = listOf(recording))

        press(Key.Menu)
        compose.waitUntilAtLeastOneExists(hasText("Stop recording"), TIMEOUT_MILLIS)
        compose.onNode(hasText("Stop recording") and hasClickAction()).performClick()

        assertThat(chosen).containsExactly(RecordingsRow.Recorded(recording) to ItemOption.StopRecording)
    }

    @Test
    fun `a booking that starts while it's being asked about is no longer asked about`() {
        show(recordings = listOf(booking(1)))
        press(Key.DirectionCenter)
        compose.waitUntilAtLeastOneExists(hasText("Cancel recording"), TIMEOUT_MILLIS)
        compose.onNode(hasText("Cancel recording") and hasClickAction()).performClick()
        compose.waitUntilAtLeastOneExists(hasText("Keep it"), TIMEOUT_MILLIS)

        shown = listOf(booking(1).copy(status = RecordingStatus.RECORDING))

        compose.waitUntilDoesNotExist(hasText("Keep it"), TIMEOUT_MILLIS)
    }

    @Test
    fun `Menu on a finished recording opens its options panel instead of playing it`() {
        show(recordings = listOf(completed(1)))

        press(Key.Menu)

        compose.waitUntilAtLeastOneExists(hasText("Delete"), TIMEOUT_MILLIS)
        assertThat(played).isEmpty()
    }

    @Test
    fun `Delete asks first, and backing out keeps the recording`() {
        show(recordings = listOf(completed(1)))

        press(Key.Menu)
        compose.waitUntilAtLeastOneExists(hasText("Delete"), TIMEOUT_MILLIS)
        compose.onNode(hasText("Delete") and hasClickAction()).performClick()
        compose.waitUntilAtLeastOneExists(hasText("Keep it"), TIMEOUT_MILLIS)
        captureScreen("recordings_delete_confirm")
        Espresso.pressBack()
        compose.waitForIdle()

        compose.onNode(hasText("Keep it")).assertDoesNotExist()
        assertThat(chosen).isEmpty()
    }

    @Test
    fun `the delete confirmation starts on Keep it, so a second press of OK deletes nothing`() {
        show(recordings = listOf(completed(1)))

        press(Key.Menu)
        compose.waitUntilAtLeastOneExists(hasText("Delete"), TIMEOUT_MILLIS)
        compose.onNode(hasText("Delete") and hasClickAction()).performClick()
        compose.waitUntilAtLeastOneExists(hasText("Keep it"), TIMEOUT_MILLIS)
        compose.waitForIdle()

        compose.onNode(hasText("Keep it") and hasClickAction()).assertIsFocused()
    }

    @Test
    fun `confirming Delete deletes the recording`() {
        val finished = completed(1)
        show(recordings = listOf(finished))

        press(Key.Menu)
        compose.waitUntilAtLeastOneExists(hasText("Delete"), TIMEOUT_MILLIS)
        compose.onNode(hasText("Delete") and hasClickAction()).performClick()
        compose.waitUntilAtLeastOneExists(hasText("Keep it"), TIMEOUT_MILLIS)
        compose.onNode(hasText("Delete") and hasClickAction()).performClick()

        assertThat(chosen).containsExactly(RecordingsRow.Recorded(finished) to ItemOption.DeleteRecording)
    }

    @Test
    fun `a recording in progress shows a Recording-red dot, which turns On accent while the row has the Highlight`() {
        show(recordings = listOf(completed(1), completed(2, RecordingStatus.RECORDING)))

        // In progress sorts first, so it starts with the Highlight.
        compose.onNodeWithText("Recording", useUnmergedTree = true).assertExists()
        assertThat(dotColour()).isEqualTo(SetteeColors.OnAccent)
        press(Key.DirectionDown)
        assertThat(dotColour()).isEqualTo(SetteeColors.Recording)
    }

    @Test
    fun `a failed recording says what went wrong, with the detail underneath`() {
        val failed = completed(1, RecordingStatus.FAILED)
            .copy(error = ViewerError("The channel stopped.", "HTTP 404").stored())
        show(recordings = listOf(failed))

        row("Recorded 1").assert(hasText("The channel stopped.", substring = true))
        compose.onNodeWithTag(ERROR_DETAIL_TAG, useUnmergedTree = true).assertTextEquals("HTTP 404")
    }

    @Test
    fun `a Reminder that will switch to its channel by itself says so, and one that only reminds doesn't`() {
        show(reminders = listOf(reminder(1, autoTune = true), reminder(2, autoTune = false)))

        row("Reminder 1").assert(hasText("Auto-switch", substring = true))
        row("Reminder 2").assert(hasText("Auto-switch", substring = true).not())
    }

    @Test
    fun `a recording says whose it is only when there's more than one Profile`() {
        val mine = completed(1).copy(profileId = 7)
        show(recordings = listOf(mine), profileNames = mapOf(7L to "Sam"))
        row("Recorded 1").assert(hasText("For Sam", substring = true).not())
    }

    @Test
    fun `with several Profiles a recording says whose it is`() {
        val mine = completed(1).copy(profileId = 7)
        show(recordings = listOf(mine), profileNames = mapOf(7L to "Sam", 8L to "Alex"))
        row("Recorded 1").assert(hasText("For Sam", substring = true))
    }

    @Test
    fun `a booking that clashes on its provider says so`() {
        val a = booking(1)
        val b = booking(1).copy(id = 2, title = "Booking 2")
        show(recordings = listOf(a, b))
        row("Booking 2").assert(hasText("Overlaps another recording", substring = true))
    }

    @Test
    fun `with nothing booked, recorded or linked the screen says how to record`() {
        show()
        compose.onNodeWithText("No recordings yet", substring = true).assertExists()
        captureScreen("recordings_empty")
    }

    private fun deleteHighlighted() {
        press(Key.Menu)
        compose.waitUntilAtLeastOneExists(hasText("Delete"), TIMEOUT_MILLIS)
        compose.onNode(hasText("Delete") and hasClickAction()).performClick()
        compose.waitUntilAtLeastOneExists(hasText("Keep it"), TIMEOUT_MILLIS)
        compose.onNode(hasText("Delete") and hasClickAction()).performClick()
        compose.waitForIdle()
    }

    private fun dotColour(): Color {
        val image = compose.onNodeWithTag("recording-dot", useUnmergedTree = true).captureToImage().toPixelMap()
        return image[image.width / 2, image.height / 2]
    }

    private fun booking(id: Long) = Recording(
        id = id,
        channelId = id,
        sourceId = 1,
        channelName = "Channel $id",
        logoUrl = "https://example.com/logo$id.png",
        title = "Booking $id",
        filePath = "/rec/$id.ts",
        streamUrl = "",
        scheduledStartMillis = NOW + id * HOUR,
        scheduledEndMillis = NOW + (id + 1) * HOUR,
        status = RecordingStatus.SCHEDULED,
    )

    private fun completed(id: Long, status: RecordingStatus = RecordingStatus.COMPLETED) = Recording(
        id = id,
        channelId = id,
        sourceId = 1,
        channelName = "Channel $id",
        logoUrl = "https://example.com/logo$id.png",
        title = "Recorded $id",
        filePath = "/rec/$id.ts",
        streamUrl = "",
        startedAtMillis = NOW - id * HOUR,
        endedAtMillis = NOW - id * HOUR + 45 * MINUTE,
        sizeBytes = 1_500_000_000L,
        status = status,
    )

    private fun reminder(id: Long, autoTune: Boolean) = Reminder(
        id = id,
        channelId = id,
        source = "Home",
        streamId = "$id",
        channelName = "Channel $id",
        title = "Reminder $id",
        startUtcMillis = NOW + id * HOUR,
        endUtcMillis = NOW + (id + 1) * HOUR,
        autoTune = autoTune,
    )

    private fun seriesLink(id: Long) = SeriesRule(
        id = id,
        channelId = id,
        source = "Home",
        streamId = "$id",
        channelName = "Channel $id",
        titleKey = "series $id",
        title = "Series $id",
    )

    private companion object {
        const val TIMEOUT_MILLIS = 5_000L
        const val NOW = 1_800_000_000_000L
        const val MINUTE = 60_000L
        const val HOUR = 60 * MINUTE
        const val LONG_LIST = 30L
    }
}
