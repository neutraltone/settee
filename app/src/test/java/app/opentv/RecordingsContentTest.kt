/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv

import android.content.Context
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
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
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
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
    private var emptied = 0

    /** What the screen lists, so a test can change it under the screen as the database would. */
    private var shown by mutableStateOf(emptyList<Recording>())

    /** The series links the screen lists, changeable under it in the same way. */
    private var links by mutableStateOf(emptyList<SeriesRule>())

    /** The Reminders the screen lists, changeable under it as one fires. */
    private var alarms by mutableStateOf(emptyList<Reminder>())

    /** What the screen says about a delete that failed, as the view model would expose it. */
    private var deleteFailure by mutableStateOf<ViewerError?>(null)

    /**
     * Shows the screen and enters it the way the rail does, by asking the content for the Highlight
     * without saying which row should have it.
     */
    private fun show(
        recordings: List<Recording> = emptyList(),
        reminders: List<Reminder> = emptyList(),
        seriesRules: List<SeriesRule> = emptyList(),
        profileNames: Map<Long, String> = emptyMap(),
        shortOfRoom: Map<Long, Long> = emptyMap(),
    ) {
        val enter = FocusRequester()
        shown = recordings
        links = seriesRules
        alarms = reminders
        compose.setTvContent {
            Box(Modifier.focusRequester(enter)) {
                RecordingsContent(
                    sections = recordingsSections(shown, alarms, links, NOW, shortOfRoom),
                    profileNames = profileNames,
                    onPlay = { played += it },
                    onRetry = { retried += it },
                    onChoose = { row, option -> chosen += row to option },
                    deleteFailure = deleteFailure,
                    onDismissFailure = { deleteFailure = null },
                    onEmptied = { emptied++ },
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

        compose.onAllNodes(hasText("Delete")).onFirst().assertExists()
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
        compose.onAllNodes(hasText("Cancel recording")).onFirst().assertExists()

        shown = listOf(booking(1).copy(status = RecordingStatus.RECORDING))

        compose.onAllNodes(hasText("Stop recording")).onFirst().assertExists()
        compose.onNode(hasText("Cancel recording")).assertDoesNotExist()
    }

    @Test
    fun `the options close when their row goes while they're open`() {
        show(recordings = listOf(booking(1), completed(2)))
        press(Key.DirectionCenter)
        compose.onAllNodes(hasText("Cancel recording")).onFirst().assertExists()

        shown = listOf(completed(2))

        compose.onAllNodes(hasText("Cancel recording")).assertCountEquals(0)
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
    fun `the Highlight goes to the row that followed the deleted one, even when another row went with it`() {
        show(recordings = (1L..5L).map { completed(it) })
        press(Key.DirectionDown)
        press(Key.DirectionDown)

        deleteHighlighted()
        // Recording 1 goes at the same time (deleted on another TV), so Recording 4 is now third.
        shown = shown.filter { it.id != 1L && it.id != 3L }
        compose.waitForIdle()

        row("Recorded 4").assertIsFocused()
    }

    @Test
    fun `a Reminder that fires while it has the Highlight passes it to the row that takes its place`() {
        show(
            recordings = listOf(completed(1)),
            reminders = listOf(reminder(1, autoTune = false), reminder(2, autoTune = false)),
        )
        row("Reminder 1").assertIsFocused()

        // The Reminder fires: nothing was pressed, it just leaves Coming up.
        alarms = alarms.map { if (it.id == 1L) it.copy(fired = true) else it }
        compose.waitForIdle()

        row("Reminder 2").assertIsFocused()
    }

    @Test
    fun `a booking cancelled elsewhere while it has the Highlight passes it to the row that takes its place`() {
        show(recordings = listOf(booking(1), booking(2), booking(3), completed(4)))
        press(Key.DirectionDown)
        row("Booking 2").assertIsFocused()

        // Cancelled on another TV: no confirm on this one.
        shown = shown.filter { it.id != 2L }
        compose.waitForIdle()

        row("Booking 3").assertIsFocused()
    }

    @Test
    fun `removing a series link and its bookings puts the Highlight on the link that moves up`() {
        val bookings = listOf(booking(1), booking(2)).map { it.copy(seriesRuleId = 3) }
        show(recordings = bookings, seriesRules = listOf(seriesLink(3), seriesLink(4), seriesLink(5)))
        press(Key.DirectionDown)
        press(Key.DirectionDown)
        row("Series 3").assertIsFocused()

        press(Key.DirectionCenter)
        compose.onAllNodes(hasText("Remove series link")).onFirst().assertExists()
        compose.onNode(hasText("Remove series link") and hasClickAction()).performClick()
        compose.onAllNodes(hasText("bookings are cancelled", substring = true)).onFirst().assertExists()
        compose.onNode(hasText("Remove series link") and hasClickAction()).performClick()
        compose.waitForIdle()
        assertThat(chosen.map { it.second }).containsExactly(ItemOption.RemoveSeriesLink)
        // Its bookings in Coming up, above it, go with it, as RecordingEngine.removeSeriesRule does.
        shown = emptyList()
        links = links.filter { it.id != 3L }
        compose.waitForIdle()

        row("Series 4").assertIsFocused()
    }

    @Test
    fun `deleting the only recording says the list has emptied`() {
        show(recordings = listOf(completed(1)))

        deleteHighlighted()
        assertThat(emptied).isEqualTo(0)
        shown = emptyList()
        compose.waitForIdle()

        assertThat(emptied).isEqualTo(1)
    }

    @Test
    fun `a recording that couldn't be deleted keeps the Highlight, and the screen says why`() {
        show(recordings = listOf(completed(1), completed(2)))
        press(Key.DirectionDown)

        deleteHighlighted()
        deleteFailure = ViewerError(NAS_DELETE_FAILED, "IO")
        compose.waitForIdle()

        compose.onNodeWithText(NAS_DELETE_FAILED).assertIsDisplayed()
        compose.onNodeWithTag(ERROR_DETAIL_TAG, useUnmergedTree = true).assertTextEquals("IO")
        row("Recorded 2").assertIsFocused()
        captureScreen("recordings_delete_failed")
    }

    @Test
    fun `the next press clears what the screen said about a failed delete, and still moves the Highlight`() {
        show(recordings = listOf(completed(1), completed(2)))
        deleteFailure = ViewerError(NAS_DELETE_FAILED, "IO")
        compose.waitForIdle()

        press(Key.DirectionDown)

        compose.onNodeWithText(NAS_DELETE_FAILED).assertDoesNotExist()
        row("Recorded 2").assertIsFocused()
    }

    @Test
    fun `leaving Recordings for the rail clears what the screen said about a failed delete`() {
        val rail = FocusRequester()
        val enter = FocusRequester()
        deleteFailure = ViewerError(NAS_DELETE_FAILED, "IO")
        compose.setTvContent {
            Row {
                Box(Modifier.size(RAIL).focusRequester(rail).focusable())
                Box(Modifier.focusRequester(enter)) {
                    RecordingsContent(
                        sections = recordingsSections(listOf(completed(1)), emptyList(), emptyList(), NOW),
                        profileNames = emptyMap(),
                        onPlay = {},
                        onRetry = {},
                        onChoose = { _, _ -> },
                        deleteFailure = deleteFailure,
                        onDismissFailure = { deleteFailure = null },
                        now = { NOW },
                    )
                }
            }
        }
        compose.runOnIdle { enter.requestFocus() }
        compose.waitForIdle()
        compose.onNodeWithText(NAS_DELETE_FAILED).assertIsDisplayed()

        compose.runOnIdle { rail.requestFocus() }
        compose.waitForIdle()

        compose.onNodeWithText(NAS_DELETE_FAILED).assertDoesNotExist()
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
        compose.onAllNodes(hasText("Cancel recording")).onFirst().assertExists()
        captureScreen("recordings_options")
        compose.onNode(hasText("Cancel recording") and hasClickAction()).performClick()
        compose.onAllNodes(hasText("Keep it")).onFirst().assertExists()
        assertThat(chosen).isEmpty()
        compose.onNode(hasText("Cancel recording") and hasClickAction()).performClick()

        assertThat(chosen).containsExactly(RecordingsRow.Booking(booked, clashes = false) to ItemOption.CancelRecording)
    }

    @Test
    fun `cancelling a Reminder asks first, and Keep it keeps it`() {
        show(reminders = listOf(reminder(1, autoTune = false)))

        press(Key.DirectionCenter)
        compose.onAllNodes(hasText("Cancel reminder")).onFirst().assertExists()
        compose.onNode(hasText("Cancel reminder") and hasClickAction()).performClick()
        compose.onAllNodes(hasText("Keep it")).onFirst().assertExists()
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
        compose.onAllNodes(hasText("Cancel reminder")).onFirst().assertExists()
        compose.onNode(hasText("Cancel reminder") and hasClickAction()).performClick()
        compose.onAllNodes(hasText("Keep it")).onFirst().assertExists()
        compose.onNode(hasText("Cancel reminder") and hasClickAction()).performClick()

        assertThat(chosen).containsExactly(RecordingsRow.OfReminder(set) to ItemOption.CancelReminder)
    }

    @Test
    fun `removing a series link asks first, and says its bookings go with it`() {
        show(seriesRules = listOf(seriesLink(1)))

        press(Key.DirectionCenter)
        compose.onAllNodes(hasText("Remove series link")).onFirst().assertExists()
        compose.onNode(hasText("Remove series link") and hasClickAction()).performClick()

        compose.onAllNodes(hasText("bookings are cancelled", substring = true)).onFirst().assertExists()
        assertThat(chosen).isEmpty()
    }

    @Test
    fun `stopping a recording doesn't ask, as nothing is lost`() {
        val recording = completed(1, RecordingStatus.RECORDING)
        show(recordings = listOf(recording))

        press(Key.Menu)
        compose.onAllNodes(hasText("Stop recording")).onFirst().assertExists()
        compose.onNode(hasText("Stop recording") and hasClickAction()).performClick()

        assertThat(chosen).containsExactly(RecordingsRow.Recorded(recording) to ItemOption.StopRecording)
    }

    @Test
    fun `a booking that starts while it's being asked about is no longer asked about`() {
        show(recordings = listOf(booking(1)))
        press(Key.DirectionCenter)
        compose.onAllNodes(hasText("Cancel recording")).onFirst().assertExists()
        compose.onNode(hasText("Cancel recording") and hasClickAction()).performClick()
        compose.onAllNodes(hasText("Keep it")).onFirst().assertExists()

        shown = listOf(booking(1).copy(status = RecordingStatus.RECORDING))

        compose.onAllNodes(hasText("Keep it")).assertCountEquals(0)
    }

    @Test
    fun `Menu on a finished recording opens its options panel instead of playing it`() {
        show(recordings = listOf(completed(1)))

        press(Key.Menu)

        compose.onAllNodes(hasText("Delete")).onFirst().assertExists()
        assertThat(played).isEmpty()
    }

    @Test
    fun `Delete asks first, and backing out keeps the recording`() {
        show(recordings = listOf(completed(1)))

        press(Key.Menu)
        compose.onAllNodes(hasText("Delete")).onFirst().assertExists()
        compose.onNode(hasText("Delete") and hasClickAction()).performClick()
        compose.onAllNodes(hasText("Keep it")).onFirst().assertExists()
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
        compose.onAllNodes(hasText("Delete")).onFirst().assertExists()
        compose.onNode(hasText("Delete") and hasClickAction()).performClick()
        compose.onAllNodes(hasText("Keep it")).onFirst().assertExists()
        compose.waitForIdle()

        compose.onNode(hasText("Keep it") and hasClickAction()).assertIsFocused()
    }

    @Test
    fun `confirming Delete deletes the recording`() {
        val finished = completed(1)
        show(recordings = listOf(finished))

        press(Key.Menu)
        compose.onAllNodes(hasText("Delete")).onFirst().assertExists()
        compose.onNode(hasText("Delete") and hasClickAction()).performClick()
        compose.onAllNodes(hasText("Keep it")).onFirst().assertExists()
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
        // One cut off when Settee closed, as a cold start stores it (#474).
        val sentence = ApplicationProvider.getApplicationContext<Context>()
            .getString(R.string.rec_error_interrupted)
        val failed = completed(1, RecordingStatus.FAILED)
            .copy(error = ViewerError(sentence, "Interrupted").stored())
        show(recordings = listOf(failed))

        row("Recorded 1").assert(hasText(sentence, substring = true))
        compose.onNodeWithTag(ERROR_DETAIL_TAG, useUnmergedTree = true).assertTextEquals("Interrupted")
        // The bare word is only the detail line, never the sentence.
        compose.onNodeWithText("Interrupted", useUnmergedTree = true).assert(hasTestTag(ERROR_DETAIL_TAG))
        captureScreen("recordings_interrupted")
    }

    @Test
    fun `a recording cut short plays on OK, shows its length and size, and says why it stopped`() {
        val cutShort = completed(1).copy(error = ViewerError(STORAGE_FULL, "StorageFull").stored())
        show(recordings = listOf(cutShort))

        row("Recorded 1").assert(hasText("45m", substring = true))
            .assert(hasText("1.5 GB", substring = true))
            .assert(hasText(STORAGE_FULL, substring = true))
        compose.onNodeWithTag(ERROR_DETAIL_TAG, useUnmergedTree = true).assertTextEquals("StorageFull")
        captureScreen("recordings_cut_short")
        press(Key.DirectionCenter)
        assertThat(played).containsExactly(cutShort)
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
    fun `a booking that may not fit says how long there's room for, and one that fits says nothing`() {
        show(recordings = listOf(booking(1), booking(2)), shortOfRoom = mapOf(1L to 42L))

        row("Booking 1").assert(hasText("Room for about 40 minutes, so it may not fit", substring = true))
        row("Booking 2").assert(hasText("may not fit", substring = true).not())
        captureScreen("recordings_may_not_fit")
    }

    @Test
    fun `with nothing booked, recorded or linked the screen says how to record`() {
        show()
        compose.onNodeWithText("No recordings yet", substring = true).assertExists()
        captureScreen("recordings_empty")
    }

    private fun deleteHighlighted() {
        press(Key.Menu)
        compose.onAllNodes(hasText("Delete")).onFirst().assertExists()
        compose.onNode(hasText("Delete") and hasClickAction()).performClick()
        compose.onAllNodes(hasText("Keep it")).onFirst().assertExists()
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
        const val NOW = 1_800_000_000_000L
        const val MINUTE = 60_000L
        const val HOUR = 60 * MINUTE
        const val LONG_LIST = 30L
        val RAIL = 48.dp
        const val NAS_DELETE_FAILED = "Couldn't delete this from your NAS. Check it's on, then try deleting it again."
        const val STORAGE_FULL = "This TV ran out of room for recordings. Delete some you've watched to make space."
    }
}
