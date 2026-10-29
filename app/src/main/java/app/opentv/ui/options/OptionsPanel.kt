/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv.ui.options

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
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
 * Select straight away does the likeliest thing. Back or choosing an option closes it.
 */
@Composable
fun OptionsPanel(title: String, options: List<ItemOption>, onChoose: (ItemOption) -> Unit, onDismiss: () -> Unit) {
    val first = remember { FocusRequester() }
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {},
        title = { Text(title) },
        text = {
            Column {
                // Requested from inside the dialog: its window composes after the caller's, and
                // a FocusRequester asked before it is attached throws.
                LaunchedEffect(first) {
                    withFrameNanos {}
                    runCatching { first.requestFocus() }
                }
                options.forEachIndexed { i, option ->
                    TextButton(
                        onClick = { onChoose(option) },
                        modifier = Modifier.fillMaxWidth().then(
                            if (i ==
                                0
                            ) {
                                Modifier.focusRequester(first)
                            } else {
                                Modifier
                            },
                        ),
                    ) {
                        Text(stringResource(option.label))
                    }
                }
            }
        },
    )
}

private val ItemOption.label: Int
    get() = when (this) {
        ItemOption.MarkWatched -> R.string.options_mark_watched
        ItemOption.MarkUnwatched -> R.string.options_mark_unwatched
        ItemOption.RemoveFromContinueWatching -> R.string.options_remove_continue_watching
        ItemOption.AddFavourite -> R.string.options_add_favourite
        ItemOption.RemoveFavourite -> R.string.options_remove_favourite
    }
