/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv.ui.recordings

import androidx.annotation.StringRes
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.LocalBringIntoViewSpec
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Repeat
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import app.opentv.R
import app.opentv.core.ViewerError
import app.opentv.data.model.Recording
import app.opentv.ui.OnShellReturn
import app.opentv.ui.design.ERROR_DETAIL_TAG
import app.opentv.ui.design.ErrorMessage
import app.opentv.ui.design.HighlightStyle
import app.opentv.ui.design.HighlightSurface
import app.opentv.ui.design.Icon
import app.opentv.ui.design.Message
import app.opentv.ui.design.ScreenFrame
import app.opentv.ui.design.SetteeColors
import app.opentv.ui.design.SetteeShapes
import app.opentv.ui.design.SetteeType
import app.opentv.ui.design.Text
import app.opentv.ui.options.ChoicePanel
import app.opentv.ui.options.ItemOption
import app.opentv.ui.options.OptionsPanel
import app.opentv.ui.options.RowHighlight
import app.opentv.ui.options.label
import app.opentv.ui.options.rememberRowHighlight
import app.opentv.ui.railReturnItem
import coil.compose.AsyncImage

/**
 * The Recordings screen's list (docs/LOOK.md, Recordings): [sections] as rows under their headings,
 * each taking the Highlight whole. [profileNames] are every Profile's, by id; a row says whose it is
 * only when there's more than one.
 *
 * OK plays a recording ([onPlay]) or retries a failed one ([onRetry]); everything else is in the
 * row's options panel, and what's picked there reaches [onChoose].
 *
 * [deleteFailure] is why a recording the viewer deleted is still here, shown under the title, out of
 * the Highlight's way, until their next press or the Highlight leaves the screen, either of which
 * tells [onDismissFailure].
 *
 * The Highlight passes to the next row when its row goes (or the one before, for the last), and
 * [onEmptied] is told when it goes with the last row and there's nowhere left in the list for it.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun RecordingsContent(
    sections: List<RecordingsSection>,
    profileNames: Map<Long, String>,
    onPlay: (Recording) -> Unit,
    onRetry: (Recording) -> Unit,
    onChoose: (RecordingsRow, ItemOption) -> Unit,
    modifier: Modifier = Modifier,
    deleteFailure: ViewerError? = null,
    onDismissFailure: () -> Unit = {},
    onEmptied: () -> Unit = {},
    now: () -> Long = System::currentTimeMillis,
) {
    // By key, not the row: the list can change under an open panel (a booking starts, a recording
    // ends), and the panel must act on the row as it is now, or close if it has gone.
    val panels = remember { Panels() }
    var hadFocus by remember { mutableStateOf(false) }
    val rows = remember(sections) { sections.flatMap { it.rows } }
    val listState = rememberLazyListState()
    val items = remember(sections) { rowItems(sections) }
    val highlight = rememberRowHighlight(
        rows,
        listState,
        itemIndex = { items[it] },
        keepScroll = true,
        onEmptied = onEmptied,
    ) { it.key }
    val rowReturn = rememberRowReturn(sections, highlight)
    val room = with(LocalDensity.current) { HEADING_ROOM.toPx() }
    ScreenFrame(modifier) {
        // Also cleared when the Highlight leaves the screen (for the rail, or another tab), whose
        // presses the list never sees: the screen stays built under the others, and so would this.
        // On leaving only: the first report, before the Highlight has come in, says it isn't here.
        Column(
            Modifier.onFocusChanged {
                if (hadFocus && !it.hasFocus && deleteFailure != null) onDismissFailure()
                hadFocus = it.hasFocus
            },
        ) {
            Text(stringResource(R.string.rec_screen_title), SetteeType.ScreenTitle)
            Spacer(Modifier.height(HEADING_GAP))
            deleteFailure?.let {
                ErrorMessage(
                    it.sentence,
                    it.detail,
                    Modifier.padding(bottom = HEADING_GAP),
                    textAlign = TextAlign.Start,
                )
            }
            // Cleared on the press rather than when focus moves, so it stays while the Highlight
            // settles back on the row after the delete panels close.
            val dismissOnPress = Modifier.onPreviewKeyEvent { event ->
                if (deleteFailure != null && event.type == KeyEventType.KeyDown) onDismissFailure()
                false
            }
            if (sections.isEmpty()) {
                Message(stringResource(R.string.rec_empty_state))
            } else {
                CompositionLocalProvider(LocalBringIntoViewSpec provides remember(room) { RoomForHeading(room) }) {
                    LazyColumn(
                        dismissOnPress,
                        state = listState,
                        verticalArrangement = Arrangement.spacedBy(ROW_GAP),
                    ) {
                        var index = 0
                        sections.forEach { section ->
                            item(key = section.heading, contentType = "heading") {
                                Text(
                                    stringResource(section.heading.label),
                                    SetteeType.Section,
                                    modifier = Modifier.padding(top = HEADING_GAP),
                                )
                            }
                            section.rows.forEach { row ->
                                val rowIndex = index++
                                railReturnItem(key = row.key, contentType = "row") {
                                    RecordingsRowItem(
                                        row = row,
                                        profileNames = profileNames.takeIf { it.size > 1 }.orEmpty(),
                                        onClick = {
                                            when (val ok = row.okAction(now())) {
                                                is OkAction.Play -> onPlay(ok.recording)
                                                is OkAction.Retry -> onRetry(ok.recording)
                                                null -> panels.optionsOn = row.key
                                            }
                                        },
                                        onOptions = { panels.optionsOn = row.key },
                                        modifier = highlight.modifier(rowIndex, row.key)
                                            .then(rowReturn.modifierFor(row.key)),
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
    RowPanels(panels, rows, onChoose)
}

/**
 * Where the Highlight goes back to when Back to the main screen couldn't put it straight back on its
 * row (KeptShell asks, [OnShellReturn]): the row last highlighted, by key so it's found wherever the
 * list has moved it, and the first row when it has gone. Saved, so it outlives the screen being built
 * again under a player. Without this nothing in Recordings answered, and the Highlight fell to the
 * rail (#432). Through [RowHighlight], the list's one way of asking for a row.
 *
 * Its [RowReturn.modifierFor] goes on each row, after [RowHighlight.modifier].
 */
@Composable
private fun rememberRowReturn(sections: List<RecordingsSection>, highlight: RowHighlight): RowReturn {
    val last = rememberSaveable { mutableStateOf<String?>(null) }
    val latest by rememberUpdatedState(sections)
    // Built again after Android let the app go, the list starts empty until the database answers.
    OnShellReturn(ready = sections.isNotEmpty()) {
        val rows = latest.flatMap { it.rows }.map { it.key }
        val target = last.value?.takeIf { it in rows } ?: rows.firstOrNull() ?: return@OnShellReturn
        highlight.aimAt(target)
    }
    return remember(last) { RowReturn(last) }
}

/** Keeps [last] on the row that last had the Highlight. */
private class RowReturn(private val last: MutableState<String?>) {
    // One per row, kept, so a row composed again gets the same modifier rather than a new one to apply.
    private val tracking = mutableMapOf<String, Modifier>()

    fun modifierFor(row: String): Modifier =
        tracking.getOrPut(row) { Modifier.onFocusChanged { if (it.hasFocus) last.value = row } }
}

/** Each row's place among the list's items, headings and all. */
private fun rowItems(sections: List<RecordingsSection>): List<Int> = buildList {
    var item = 0
    sections.forEach { section ->
        item++
        section.rows.forEach { _ -> add(item++) }
    }
}

/** Which row's options are open, by key, and what it's being asked about. */
private class Panels {
    var optionsOn by mutableStateOf<String?>(null)
    var asking by mutableStateOf<Asking?>(null)
}

/** A row's options panel, and the question before an option that removes the row. */
@Composable
private fun RowPanels(panels: Panels, rows: List<RecordingsRow>, onChoose: (RecordingsRow, ItemOption) -> Unit) {
    rows.firstOrNull { it.key == panels.optionsOn }?.let { row ->
        OptionsPanel(
            title = row.title,
            options = row.options().options(),
            onChoose = { option ->
                panels.optionsOn = null
                val question = (option as? ItemOption.RecordingAction)?.question
                if (question == null) onChoose(row, option) else panels.asking = Asking(row.key, option, question)
            },
            onDismiss = { panels.optionsOn = null },
        )
    }
    // Asked only while the row still offers it: a booking that has started can't be cancelled.
    val ask = panels.asking ?: return
    rows.firstOrNull { it.key == ask.row && ask.option in it.options().options() }?.let { row ->
        ChoicePanel(
            title = stringResource(ask.question, row.title),
            choices = Answer.entries,
            labelOf = { if (it == Answer.Keep) stringResource(R.string.rec_delete_keep) else ask.option.label() },
            onChoose = { answer ->
                panels.asking = null
                if (answer == Answer.Go) onChoose(row, ask.option)
            },
            onDismiss = { panels.asking = null },
        )
    }
}

/** A row's option waiting on the viewer's answer to [question]. */
private class Asking(val row: String, val option: ItemOption, @StringRes val question: Int)

private enum class Answer {
    // First, so it's where the Highlight starts: a second press of OK must not remove anything.
    Keep,
    Go,
}

/**
 * What's asked before an option that removes its row, or null for one that doesn't: stopping leaves
 * the recording, finished. A deleted file can't be brought back, and a booking, Reminder or series
 * link is gone until it's set up again from the guide.
 */
private val ItemOption.RecordingAction.question: Int?
    get() = when (this) {
        ItemOption.StopRecording -> null
        ItemOption.DeleteRecording -> R.string.rec_delete_confirm
        ItemOption.CancelRecording -> R.string.rec_cancel_recording_confirm
        ItemOption.CancelReminder -> R.string.rec_cancel_reminder_confirm
        ItemOption.RemoveSeriesLink -> R.string.rec_series_remove_confirm
    }

private val RecordingsHeading.label
    get() = when (this) {
        RecordingsHeading.ComingUp -> R.string.rec_heading_coming_up
        RecordingsHeading.Recorded -> R.string.rec_section_recorded
        RecordingsHeading.SeriesLinks -> R.string.rec_section_series_links
    }

private val RecordingsRow.key: String
    get() = when (this) {
        is RecordingsRow.Booking -> "rec-${recording.id}"
        is RecordingsRow.Recorded -> "rec-${recording.id}"
        is RecordingsRow.OfReminder -> "rem-${reminder.id}"
        is RecordingsRow.SeriesLink -> "series-${rule.id}"
    }

@Composable
private fun RecordingsRowItem(
    row: RecordingsRow,
    profileNames: Map<Long, String>,
    onClick: () -> Unit,
    onOptions: () -> Unit,
    modifier: Modifier = Modifier,
) {
    HighlightSurface(
        onClick = onClick,
        style = HighlightStyle.Row,
        modifier = modifier.fillMaxWidth(),
        onOptions = onOptions,
    ) { highlighted ->
        val text = if (highlighted) SetteeColors.OnAccent else SetteeColors.Text
        val quiet = if (highlighted) SetteeColors.OnAccent else SetteeColors.QuietText
        Row(
            Modifier.padding(horizontal = ROW_PADDING_SIDE, vertical = ROW_PADDING_TOP),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Leading(row, quiet)
            Spacer(Modifier.width(ROW_PADDING_SIDE))
            Column(Modifier.weight(1f)) {
                Text(row.title, SetteeType.RowTitle, color = text, maxLines = 1)
                if (row.inProgress) RecordingNow(highlighted, text)
                Text(row.line(profileNames), SetteeType.Body, color = quiet, maxLines = 2)
                row.reason()?.let { Reason(it, text, quiet) }
                // In the row's quiet colour, not coral: coral marks only what the remote can press.
                if (row is RecordingsRow.Booking && row.clashes) {
                    Text(stringResource(R.string.rec_clashes), SetteeType.Body, color = quiet, maxLines = 1)
                }
                (row as? RecordingsRow.Booking)?.roomLeftMinutes?.let {
                    Text(mayNotFit(LocalResources.current, it), SetteeType.Body, color = quiet, maxLines = 1)
                }
            }
        }
    }
}

/** The channel's logo, or what stands in for one: a bell for a Reminder, a repeat for a series link. */
@Composable
private fun Leading(row: RecordingsRow, tint: Color) {
    Box(Modifier.size(LOGO_SIZE), contentAlignment = Alignment.Center) {
        when (row) {
            is RecordingsRow.OfReminder -> Icon(Icons.Filled.Notifications, contentDescription = null, tint = tint)
            is RecordingsRow.SeriesLink -> Icon(Icons.Filled.Repeat, contentDescription = null, tint = tint)
            is RecordingsRow.Booking, is RecordingsRow.Recorded -> row.recording?.logoUrl?.let { logo ->
                AsyncImage(
                    model = logo,
                    contentDescription = null,
                    modifier = Modifier.size(LOGO_SIZE).clip(SetteeShapes.Thumbnail),
                )
            }
        }
    }
}

/**
 * Why a recording stopped and its detail line, as `ErrorMessage` lays them out, but in the row's own
 * colours: `ErrorMessage`'s are for the Background, and a highlighted row is coral.
 */
@Composable
private fun Reason(reason: ViewerError, text: Color, quiet: Color) {
    Text(reason.sentence, SetteeType.Body, color = text, maxLines = 2)
    if (reason.detail.isNotBlank()) {
        Text(
            reason.detail,
            SetteeType.Body,
            color = quiet,
            maxLines = 1,
            modifier = Modifier.testTag(ERROR_DETAIL_TAG),
        )
    }
}

/**
 * The dot and the word that say a recording is capturing now. Recording red on coral is about
 * 1.4:1, so on a highlighted row the dot takes the row's text colour instead.
 */
@Composable
private fun RecordingNow(highlighted: Boolean, text: Color) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier
                .size(DOT_SIZE)
                .testTag("recording-dot")
                .background(if (highlighted) SetteeColors.OnAccent else SetteeColors.Recording, CircleShape),
        )
        Spacer(Modifier.width(DOT_GAP))
        Text(stringResource(R.string.rec_in_progress), SetteeType.Body, color = text, maxLines = 1)
    }
}

private val HEADING_GAP = 12.dp

// A heading's line, the gap above it and the gap between it and its first row.
private val HEADING_ROOM = 40.dp
private val ROW_GAP = 4.dp
private val ROW_PADDING_SIDE = 16.dp
private val ROW_PADDING_TOP = 10.dp
private val LOGO_SIZE = 44.dp
private val DOT_SIZE = 10.dp
private val DOT_GAP = 8.dp
