/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv.update

import android.app.Application
import android.content.Context
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.edit
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import app.opentv.BuildConfig
import app.opentv.R
import app.opentv.core.ErrorWording
import app.opentv.core.ServiceLocator
import app.opentv.ui.design.ErrorMessage
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * The one-line entry point: drop [UpdateGate] into the top-level layout and a sideloaded
 * install will notice and offer its own updates. It renders nothing until there is something
 * to say, so it is safe to place unconditionally over the whole app.
 */
@Composable
fun UpdateGate(viewModel: UpdateViewModel = viewModel()) {
    val state by viewModel.state.collectAsState()

    when (val s = state) {
        UpdateUiState.Idle -> Unit

        is UpdateUiState.Available -> UpdateAvailableDialog(
            update = s.update,
            installedVersion = BuildConfig.VERSION_NAME,
            onInstall = viewModel::install,
            onLater = viewModel::dismiss,
        )

        is UpdateUiState.Downloading -> AlertDialog(
            onDismissRequest = {}, // a download in flight should not be dismissed by a stray click
            confirmButton = {},
            title = { Text("Downloading update…") },
            text = {
                Column {
                    if (s.fraction >= 0f) {
                        LinearProgressIndicator(
                            progress = { s.fraction },
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Text("${(s.fraction * 100).toInt()}%", Modifier.padding(top = 8.dp))
                    } else {
                        LinearProgressIndicator(Modifier.fillMaxWidth())
                    }
                }
            },
        )

        is UpdateUiState.Failed -> AlertDialog(
            onDismissRequest = viewModel::dismiss,
            confirmButton = {
                TextButton(onClick = viewModel::install) { Text(stringResource(R.string.common_try_again)) }
            },
            dismissButton = {
                TextButton(onClick = viewModel::dismiss) { Text(stringResource(R.string.update_close)) }
            },
            title = { Text(stringResource(R.string.update_failed_title)) },
            text = {
                val wording = ServiceLocator.get(LocalContext.current).errorWording
                val reason = wording.ofHardware(R.string.update_failed_tv, R.string.update_failed_device, s.detail)
                ErrorMessage(reason.sentence, reason.detail, textAlign = TextAlign.Start)
            },
        )
    }
}

/** "Update available", with the new release's notes. */
@Composable
fun UpdateAvailableDialog(
    update: UpdateChecker.Update,
    installedVersion: String,
    onInstall: () -> Unit,
    onLater: () -> Unit,
) {
    val scroll = rememberScrollState()
    val dpadScroll = rememberDpadScroll(scroll)
    AlertDialog(
        onDismissRequest = onLater,
        confirmButton = {
            TextButton(onClick = onInstall, modifier = Modifier.startsFocused().then(dpadScroll)) { Text("Update") }
        },
        dismissButton = { TextButton(onClick = onLater, modifier = dpadScroll) { Text("Later") } },
        title = { Text("Update available") },
        text = {
            Column(Modifier.verticalScroll(scroll)) {
                Text("Settee ${update.versionName} is available. You have $installedVersion.")
                update.notes?.let { notes ->
                    Box(Modifier.padding(top = 12.dp)) { ReleaseNotesBody(listOf(notes)) }
                }
            }
        },
    )
}

sealed interface UpdateUiState {
    data object Idle : UpdateUiState
    data class Available(val update: UpdateChecker.Update) : UpdateUiState
    data class Downloading(val update: UpdateChecker.Update, val fraction: Float) : UpdateUiState

    /** [detail] is the download's failure, for the detail line. */
    data class Failed(val update: UpdateChecker.Update, val detail: String) : UpdateUiState
}

/**
 * One shared update state so the manual "check for updates" (About) and the automatic gate speak
 * with one voice. A manual check that finds a build flips this, and [UpdateGate] — mounted over the
 * whole app — shows the dialog wherever the user is standing. Previously the two had separate state
 * (and the gate was throttled to a 6-hour check), so a manual find never actually prompted.
 */
object UpdateHub {
    val state = MutableStateFlow<UpdateUiState>(UpdateUiState.Idle)
}

class UpdateViewModel(app: Application) : AndroidViewModel(app) {
    private val graph = ServiceLocator.get(app)
    private val checker = UpdateChecker(graph.httpClient, BuildConfig.VERSION_NAME)
    private val installer = ApkInstaller(graph.httpClient)

    // Backed by the shared hub so a manual check from About lights up this same gate.
    private val _state = UpdateHub.state
    val state = _state.asStateFlow()

    init {
        checkThrottled()
    }

    /**
     * Hits the network at most once every [CHECK_INTERVAL_MS]. The guide already re-syncs on
     * every launch; adding an unconditional GitHub round-trip on top of that would be rude to
     * both GitHub's rate limit and the user's connection, for information that changes rarely.
     */
    private fun checkThrottled() {
        viewModelScope.launch {
            val prefs = getApplication<Application>()
                .getSharedPreferences("opentv", Context.MODE_PRIVATE)
            val now = System.currentTimeMillis()
            if (now - prefs.getLong(KEY_LAST_CHECK, 0L) < CHECK_INTERVAL_MS) return@launch

            val outcome = checker.check()
            // A failed check (offline, or a release still missing its APK) mustn't hold the next
            // one off for 6 hours: the APK usually turns up minutes later when the job is re-run.
            if (outcome !is UpdateChecker.Outcome.CouldNotCheck) prefs.edit { putLong(KEY_LAST_CHECK, now) }
            if (outcome is UpdateChecker.Outcome.Available) _state.value = UpdateUiState.Available(outcome.update)
        }
    }

    fun install() {
        val update = when (val s = _state.value) {
            is UpdateUiState.Available -> s.update
            is UpdateUiState.Failed -> s.update
            is UpdateUiState.Downloading -> s.update
            UpdateUiState.Idle -> return
        }
        viewModelScope.launch {
            _state.value = UpdateUiState.Downloading(update, 0f)
            runCatching {
                installer.downloadAndInstall(
                    context = getApplication(),
                    url = update.apkUrl,
                    expectedBytes = update.apkSizeBytes,
                ) { fraction -> _state.value = UpdateUiState.Downloading(update, fraction) }
            }.onSuccess {
                // The system installer is now front-and-centre; step our dialog aside.
                _state.value = UpdateUiState.Idle
            }.onFailure {
                _state.value = UpdateUiState.Failed(update, ErrorWording.codeOf(it))
            }
        }
    }

    fun dismiss() {
        _state.value = UpdateUiState.Idle
    }

    private companion object {
        const val KEY_LAST_CHECK = "last_update_check"
        const val CHECK_INTERVAL_MS = 6 * 60 * 60 * 1000L // 6 hours
    }
}
