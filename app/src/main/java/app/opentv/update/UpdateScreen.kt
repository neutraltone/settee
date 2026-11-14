/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv.update

import android.app.Application
import android.content.Context
import android.content.SharedPreferences
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
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
import app.opentv.core.ViewerError
import app.opentv.ui.design.AerialTv
import app.opentv.ui.design.ErrorMessage
import app.opentv.ui.design.NotesPanel
import app.opentv.ui.design.PanelRow
import app.opentv.ui.design.PanelTitle
import app.opentv.ui.design.ProgressBar
import app.opentv.ui.design.SetteeType
import app.opentv.ui.design.SidePanel
import app.opentv.ui.design.Text
import app.opentv.ui.design.percentText
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
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

        // Back only hides it: the download carries on (withProgress keeps it hidden), and the installer
        // still opens when it's done.
        is UpdateUiState.Downloading -> UpdateDownloadingPanel(s.fraction, onBack = viewModel::dismiss)

        is UpdateUiState.Failed -> {
            val wording = ServiceLocator.get(LocalContext.current).errorWording
            UpdateFailedPanel(
                reason = wording.ofHardware(R.string.update_failed_tv, R.string.update_failed_device, s.detail),
                onTryAgain = viewModel::install,
                onClose = viewModel::dismiss,
            )
        }
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
    NotesPanel(
        title = stringResource(R.string.update_available_title),
        body = stringResource(R.string.update_available_body, update.versionName, installedVersion),
        main = stringResource(R.string.update_install),
        onMain = onInstall,
        onBack = onLater,
        others = { PanelRow(stringResource(R.string.update_later), onClick = onLater) },
    ) {
        update.notes?.let { ReleaseNotesBody(listOf(it)) }
    }
}

/**
 * The update downloading: how far through it is, when the download knows its size ([fraction] from
 * 0 to 1, or below 0 when it doesn't). A bar only ever shows real progress, so an unknown size waits
 * with the aerial TV instead.
 */
@Composable
fun UpdateDownloadingPanel(fraction: Float, onBack: () -> Unit) {
    SidePanel(onBack = onBack) {
        val title = stringResource(R.string.update_downloading)
        PanelTitle(title)
        if (fraction >= 0f) {
            Text(percentText(fraction), SetteeType.Body)
            ProgressBar(fraction, Modifier.fillMaxWidth().padding(top = 8.dp))
        } else {
            AerialTv(title)
        }
    }
}

/** The update didn't download: why, as every error says it, and the chance to try again. */
@Composable
fun UpdateFailedPanel(reason: ViewerError, onTryAgain: () -> Unit, onClose: () -> Unit) {
    SidePanel(onBack = onClose) {
        PanelTitle(stringResource(R.string.update_failed_title))
        ErrorMessage(
            reason.sentence,
            reason.detail,
            textAlign = TextAlign.Start,
            modifier = Modifier.padding(bottom = 12.dp),
        )
        PanelRow(stringResource(R.string.common_try_again), onClick = onTryAgain)
        PanelRow(stringResource(R.string.update_close), onClick = onClose)
    }
}

sealed interface UpdateUiState {
    data object Idle : UpdateUiState
    data class Available(val update: UpdateChecker.Update) : UpdateUiState
    data class Downloading(val update: UpdateChecker.Update, val fraction: Float) : UpdateUiState

    /** [detail] is the download's failure, for the detail line. */
    data class Failed(val update: UpdateChecker.Update, val detail: String) : UpdateUiState
}

/**
 * The state once the download is [fraction] through: the Downloading panel moves on, and anything
 * else stays as it is. Back sets Idle mid-download, and progress mustn't bring the panel back.
 */
fun UpdateUiState.withProgress(fraction: Float): UpdateUiState =
    if (this is UpdateUiState.Downloading) copy(fraction = fraction) else this

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
            check(prefs, now)
        }
    }

    /**
     * Checks whatever the throttle says. For a database Settee can't open (#334): a newer release is the
     * fix, and the viewer is stuck on that one screen until it arrives, so waiting out six hours since
     * the last check would leave them there.
     */
    fun checkNow() {
        viewModelScope.launch {
            val prefs = getApplication<Application>()
                .getSharedPreferences("opentv", Context.MODE_PRIVATE)
            check(prefs, System.currentTimeMillis())
        }
    }

    private suspend fun check(prefs: SharedPreferences, now: Long) {
        val outcome = checker.check()
        // A failed check (offline, or a release still missing its APK) mustn't hold the next
        // one off for 6 hours: the APK usually turns up minutes later when the job is re-run.
        if (outcome !is UpdateChecker.Outcome.CouldNotCheck) prefs.edit { putLong(KEY_LAST_CHECK, now) }
        if (outcome is UpdateChecker.Outcome.Available) _state.value = UpdateUiState.Available(outcome.update)
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
                ) { fraction -> _state.update { it.withProgress(fraction) } }
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
