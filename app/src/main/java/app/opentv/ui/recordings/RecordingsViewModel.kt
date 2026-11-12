/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv.ui.recordings

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import app.opentv.core.ServiceLocator
import app.opentv.data.model.Profile
import app.opentv.data.model.Recording
import app.opentv.data.model.RecordingStatus
import app.opentv.data.model.Reminder
import app.opentv.data.model.SeriesRule
import app.opentv.recording.RecordingStorage
import app.opentv.reminders.ReminderScheduler
import app.opentv.ui.options.ItemOption
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class RecordingsViewModel(app: Application) : AndroidViewModel(app) {
    private val graph = ServiceLocator.get(app)

    val recordings: StateFlow<List<Recording>> =
        graph.recordingRepository.observeAll()
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val reminders: StateFlow<List<Reminder>> =
        graph.reminderRepository.observeAll()
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val profiles: StateFlow<List<Profile>> =
        graph.profiles.observeAll()
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** Active series-link rules — shown as their own section so a link is visible even before its
     *  first episode has been booked (e.g. the next airing isn't in the loaded guide window yet). */
    val seriesRules: StateFlow<List<SeriesRule>> =
        graph.recordingRepository.observeRules()
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    // NB: cold-start reconciliation of orphaned "recording" rows happens once in OpenTvApp, NOT
    // here — opening this screen must never mark an actively-capturing recording as interrupted.

    fun stop(id: Long) = graph.recordingEngine.stop(id)

    /** Re-attempt a failed/interrupted recording (re-arms a future booking or restarts a live one). */
    fun retry(recording: Recording) {
        viewModelScope.launch { graph.recordingEngine.retry(recording) }
    }

    /** Delete a series link and cancel its future (not-yet-started) bookings. */
    fun removeSeriesRule(rule: SeriesRule) {
        viewModelScope.launch { graph.recordingEngine.removeSeriesRule(rule.id) }
    }

    fun cancelReminder(reminder: Reminder) {
        viewModelScope.launch {
            ReminderScheduler.cancel(getApplication(), reminder.id)
            graph.reminderRepository.delete(reminder.id)
        }
    }

    /** Cancel a booking before it starts, alarm and all. */
    fun cancel(recording: Recording) {
        viewModelScope.launch { graph.recordingEngine.cancelScheduled(recording.id) }
    }

    /** Does what the viewer picked in a row's options panel. */
    fun choose(row: RecordingsRow, option: ItemOption) {
        when (row) {
            is RecordingsRow.Booking -> if (option == ItemOption.CancelRecording) cancel(row.recording)
            is RecordingsRow.Recorded -> when (option) {
                ItemOption.StopRecording -> stop(row.recording.id)
                ItemOption.DeleteRecording -> delete(row.recording)
                else -> Unit
            }
            is RecordingsRow.OfReminder -> if (option == ItemOption.CancelReminder) cancelReminder(row.reminder)
            is RecordingsRow.SeriesLink -> if (option == ItemOption.RemoveSeriesLink) removeSeriesRule(row.rule)
        }
    }

    fun delete(recording: Recording) {
        viewModelScope.launch {
            if (recording.status == RecordingStatus.RECORDING) graph.recordingEngine.stop(recording.id)
            RecordingStorage.delete(getApplication(), graph.settings, recording.filePath)
            graph.recordingRepository.delete(recording.id)
        }
    }
}
