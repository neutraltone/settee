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

/**
 * [onNothingToShow] is called when the last thing listed goes while it has the Highlight, so it lands
 * on Recordings in the rail rather than wherever Android puts a Highlight with nowhere to go (#444).
 */
@Composable
fun RecordingsScreen(onPlay: (Recording) -> Unit, onNothingToShow: () -> Unit = {}) {
    val viewModel: RecordingsViewModel = viewModel()
    val recordings by viewModel.recordings.collectAsState()
    val reminders by viewModel.reminders.collectAsState()
    val profiles by viewModel.profiles.collectAsState()
    val seriesRules by viewModel.seriesRules.collectAsState()
    val deleteFailure by viewModel.deleteFailure.collectAsState()
    val shortOfRoom by viewModel.shortOfRoom.collectAsState()
    val sections = remember(recordings, reminders, seriesRules, shortOfRoom) {
        recordingsSections(recordings, reminders, seriesRules, System.currentTimeMillis(), shortOfRoom)
    }
    RecordingsContent(
        sections = sections,
        profileNames = remember(profiles) { profiles.associate { it.id to it.name } },
        onPlay = onPlay,
        onRetry = viewModel::retry,
        onChoose = viewModel::choose,
        deleteFailure = deleteFailure,
        onDismissFailure = viewModel::dismissDeleteFailure,
        onEmptied = onNothingToShow,
    )
}
