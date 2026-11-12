/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv.ui.recordings

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.lifecycle.viewmodel.compose.viewModel
import app.opentv.data.model.Recording

@Composable
fun RecordingsScreen(onPlay: (Recording) -> Unit) {
    val viewModel: RecordingsViewModel = viewModel()
    val recordings by viewModel.recordings.collectAsState()
    val reminders by viewModel.reminders.collectAsState()
    val profiles by viewModel.profiles.collectAsState()
    val seriesRules by viewModel.seriesRules.collectAsState()
    val sections = remember(recordings, reminders, seriesRules) {
        recordingsSections(recordings, reminders, seriesRules, System.currentTimeMillis())
    }
    RecordingsContent(
        sections = sections,
        profileNames = remember(profiles) { profiles.associate { it.id to it.name } },
        onPlay = onPlay,
        onRetry = viewModel::retry,
        onChoose = viewModel::choose,
    )
}
