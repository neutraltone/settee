/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv.ui.settings

import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import app.opentv.R
import app.opentv.core.ServiceLocator
import app.opentv.diagnostics.DiagnosticsExport
import app.opentv.diagnostics.DiagnosticsReport
import app.opentv.ui.design.ListRow
import kotlinx.coroutines.launch

/** How the pane and the full report get a report: from the app's own reporter, unless a test gives one. */
internal typealias GatherReport = suspend () -> DiagnosticsReport

/**
 * Diagnostics: the state Settee is in on this device, so "it got slow" comes with numbers. Saving to
 * the NAS comes first, as it's what OK is for here. Each part below is summed up on its row, and OK
 * on any of them opens the full report, so no row looks pressable and does nothing.
 */
@Composable
internal fun DiagnosticsPane(onOpenReport: () -> Unit, gather: GatherReport? = null) {
    val context = LocalContext.current
    val graph = remember { ServiceLocator.get(context) }
    val gathering = gather ?: graph.diagnosticsReporter::gather
    val report by produceState<DiagnosticsReport?>(null) { value = gathering() }
    val scope = rememberCoroutineScope()
    var saving by remember { mutableStateOf<SaveState>(SaveState.Idle) }

    ListRow(
        stringResource(R.string.diagnostics_save),
        onClick = {
            if (saving == SaveState.Saving) return@ListRow
            saving = SaveState.Saving
            // Pressed before the report is in, it gathers one rather than let the press do nothing.
            scope.launch {
                saving = SaveState.Done(graph.diagnosticsExport.save(report ?: gathering(), graph.clock()))
            }
        },
        note = saveNote(saving),
        detail = ((saving as? SaveState.Done)?.result as? DiagnosticsExport.Result.Failed)?.let {
            graph.errorWording.of(it.cause, R.string.sync_nas_failed).detail
        },
    )
    val shown = report
    PartRow(R.string.diagnostics_database, onOpenReport, shown?.let { databaseSummary(context, it) })
    PartRow(R.string.diagnostics_storage, onOpenReport, shown?.let { storageSummary(context, it) })
    PartRow(R.string.diagnostics_memory, onOpenReport, shown?.let { memorySummary(context, it) })
    PartRow(R.string.diagnostics_opens, onOpenReport, shown?.let { opensSummary(context, it) })
    PartRow(R.string.diagnostics_errors, onOpenReport, shown?.let { errorsSummary(context, it) })
    PartRow(
        R.string.diagnostics_device,
        onOpenReport,
        shown?.let {
            DiagnosticsSummary(
                stringResource(R.string.diagnostics_device_value, it.device.appVersion, it.device.model),
            )
        },
    )
}

private sealed interface SaveState {
    data object Idle : SaveState

    data object Saving : SaveState

    data class Done(val result: DiagnosticsExport.Result) : SaveState
}

@Composable
private fun saveNote(state: SaveState): String = when (state) {
    SaveState.Idle -> stringResource(R.string.diagnostics_save_note)
    SaveState.Saving -> stringResource(R.string.diagnostics_saving)
    is SaveState.Done -> when (val result = state.result) {
        is DiagnosticsExport.Result.Saved -> stringResource(R.string.diagnostics_saved, result.name)
        DiagnosticsExport.Result.NotConfigured -> stringResource(R.string.sync_nas_needs_setup)
        is DiagnosticsExport.Result.Failed -> stringResource(R.string.sync_nas_failed)
    }
}

/** A part of the report, summed up; its note is the failure's sentence when it couldn't be gathered. */
@Composable
private fun PartRow(@StringRes label: Int, onOpenReport: () -> Unit, summary: DiagnosticsSummary?) {
    ListRow(
        stringResource(label),
        onClick = onOpenReport,
        note = summary?.text ?: stringResource(R.string.diagnostics_gathering),
        detail = summary?.failure?.detail,
        opens = true,
    )
}
