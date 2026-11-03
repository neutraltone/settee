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
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.res.stringResource
import app.opentv.R

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
    labelOf: @Composable (ItemOption) -> String = { stringResource(it.label) },
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
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {},
        title = { Text(title) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                // Requested from inside the dialog: its window composes after the caller's, and
                // a FocusRequester asked before it is attached throws.
                LaunchedEffect(first) {
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

internal val ItemOption.label: Int
    get() = when (this) {
        ItemOption.PlayFromStart -> R.string.options_play_from_start
        ItemOption.ShowInfo -> R.string.options_show_info
        ItemOption.MarkWatched -> R.string.options_mark_watched
        ItemOption.MarkUnwatched -> R.string.options_mark_unwatched
        ItemOption.RemoveFromContinueWatching -> R.string.options_remove_continue_watching
        ItemOption.AddFavourite -> R.string.options_add_favourite
        ItemOption.RemoveFavourite -> R.string.options_remove_favourite
        ItemOption.RemoveRecentSearch -> R.string.common_remove
        ItemOption.Subtitles -> R.string.player_subtitles
        ItemOption.Audio -> R.string.player_audio
        ItemOption.Quality -> R.string.player_quality
        ItemOption.AspectRatio -> R.string.player_aspect_ratio_title
        ItemOption.SleepTimer -> R.string.settings_sleep_timer
        ItemOption.RecordNow -> R.string.rec_record_now
        ItemOption.StopRecording -> R.string.rec_stop_recording
    }
