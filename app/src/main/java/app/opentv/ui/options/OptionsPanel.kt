/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv.ui.options

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.res.stringResource
import app.opentv.R
import app.opentv.ui.channels.formatTime
import app.opentv.ui.player.OK_KEYS

/**
 * The options panel for one item, headed [title]: a button per option, the first highlighted so
 * Select straight away does the likeliest thing. Back or choosing an option closes it. [labelOf]
 * is for an option that says more than its name.
 */
@Composable
fun OptionsPanel(
    title: String,
    options: List<ItemOption>,
    onChoose: (ItemOption) -> Unit,
    onDismiss: () -> Unit,
    labelOf: @Composable (ItemOption) -> String = { it.label() },
) {
    ChoicePanel(title, options, labelOf, onChoose, onDismiss)
}

/** The panel itself, for choices that aren't [ItemOption]s. */
@Composable
internal fun <T> ChoicePanel(
    title: String,
    choices: List<T>,
    labelOf: @Composable (T) -> String,
    onChoose: (T) -> Unit,
    onDismiss: () -> Unit,
) {
    val first = remember { FocusRequester() }
    // Holding Select is one way to open this, and the highlight lands on the first option with the key
    // still down. Its repeats and release would press that option, so only a new press counts.
    var newPress by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {},
        title = { Text(title) },
        text = {
            // Scrolls because a list can outgrow the screen: focus moving down it brings each row in.
            Column(
                Modifier
                    .onPreviewKeyEvent { event ->
                        if (event.key !in OK_KEYS) return@onPreviewKeyEvent false
                        if (event.type == KeyEventType.KeyDown && event.nativeKeyEvent.repeatCount == 0) {
                            newPress = true
                        }
                        !newPress
                    }
                    .verticalScroll(rememberScrollState()),
            ) {
                // Requested from inside the dialog: its window composes after the caller's, and
                // a FocusRequester asked before it is attached throws. Asked again for each list, as
                // the row that had the highlight may be gone from the next.
                LaunchedEffect(choices) {
                    withFrameNanos {}
                    runCatching { first.requestFocus() }
                }
                choices.forEachIndexed { i, choice ->
                    TextButton(
                        onClick = { onChoose(choice) },
                        modifier = Modifier.fillMaxWidth().then(
                            if (i == 0) Modifier.focusRequester(first) else Modifier,
                        ),
                    ) {
                        Text(labelOf(choice))
                    }
                }
            }
        },
    )
}

@Composable
internal fun ItemOption.label(): String = when (this) {
    ItemOption.PlayFromStart -> stringResource(R.string.options_play_from_start)
    ItemOption.ShowInfo -> stringResource(R.string.options_show_info)
    is ItemOption.MarkAction -> label()
    ItemOption.RemoveFromContinueWatching -> stringResource(R.string.options_remove_continue_watching)
    ItemOption.AddFavourite -> stringResource(R.string.options_add_favourite)
    ItemOption.RemoveFavourite -> stringResource(R.string.options_remove_favourite)
    ItemOption.RemoveRecentSearch -> stringResource(R.string.common_remove)
    is ItemOption.PlayerAction -> label()
    is ItemOption.ChannelAction -> label()
    is ItemOption.RecordingAction -> label()
}

@Composable
private fun ItemOption.PlayerAction.label(): String = when (this) {
    ItemOption.Subtitles -> stringResource(R.string.player_subtitles)
    ItemOption.Audio -> stringResource(R.string.player_audio)
    ItemOption.Quality -> stringResource(R.string.player_quality)
    ItemOption.AspectRatio -> stringResource(R.string.player_aspect_ratio_title)
    ItemOption.SleepTimer -> stringResource(R.string.settings_sleep_timer)
}

@Composable
private fun ItemOption.MarkAction.label(): String = when (this) {
    ItemOption.MarkWatched -> stringResource(R.string.options_mark_watched)
    ItemOption.MarkUnwatched -> stringResource(R.string.options_mark_unwatched)
    ItemOption.MarkWatchedUpToHere -> stringResource(R.string.options_mark_watched_up_to_here)
    ItemOption.MarkSeasonWatched -> stringResource(R.string.options_mark_season_watched)
    ItemOption.MarkSeasonUnwatched -> stringResource(R.string.options_mark_season_unwatched)
}

@Composable
private fun ItemOption.RecordingAction.label(): String = when (this) {
    ItemOption.StopRecording -> stringResource(R.string.rec_stop_recording)
    ItemOption.DeleteRecording -> stringResource(R.string.common_delete)
    ItemOption.CancelRecording -> stringResource(R.string.rec_cancel_recording)
    ItemOption.CancelReminder -> stringResource(R.string.rec_cancel_reminder)
    ItemOption.RemoveSeriesLink -> stringResource(R.string.rec_series_remove)
}

@Composable
private fun ItemOption.ChannelAction.label(): String = when (this) {
    ItemOption.Watch -> stringResource(R.string.guide_watch)
    ItemOption.HideChannel -> stringResource(R.string.options_hide_channel)
    ItemOption.OpenInExternalPlayer -> stringResource(R.string.guide_open_external)
    ItemOption.RecordNow -> stringResource(R.string.rec_record_now)
    ItemOption.StopRecording -> stringResource(R.string.rec_stop_recording)
    is ItemOption.RecordWholeSeries -> stringResource(R.string.rec_record_series_named, programme.title)
    ItemOption.ScheduleLater -> stringResource(R.string.options_schedule_later)
    is ItemOption.LaterProgramme -> "${formatTime(programme.startUtcMillis)}   ${programme.title}"
}
