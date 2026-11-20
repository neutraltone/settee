/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv.ui.settings

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import app.opentv.R
import app.opentv.core.ServiceLocator
import app.opentv.diagnostics.DiagnosticsReport
import app.opentv.diagnostics.Gathered
import app.opentv.ui.design.ErrorMessage

/**
 * The whole report, as a screen of its own opened from Diagnostics, for reading out or photographing
 * when there's no NAS to save it to. Back returns to Diagnostics.
 */
@Composable
fun DiagnosticsReportScreen(onBack: () -> Unit, modifier: Modifier = Modifier, gather: GatherReport? = null) {
    val context = LocalContext.current
    val gathering = gather ?: remember { ServiceLocator.get(context).diagnosticsReporter::gather }
    val report by produceState<DiagnosticsReport?>(null) { value = gathering() }
    TextScreen(stringResource(R.string.diagnostics_report_title), onBack, modifier, lineGap = 6.dp) {
        val shown = report
        if (shown == null) Quiet(stringResource(R.string.diagnostics_gathering)) else Report(context, shown)
    }
}

@Composable
private fun Report(context: Context, report: DiagnosticsReport) {
    DevicePart(report)
    DatabasePart(context, report)
    StoragePart(context, report)
    MemoryPart(context, report)
    OpensPart(context, report)
    ErrorsPart(context, report)
}

@Composable
private fun DevicePart(report: DiagnosticsReport) {
    Part(R.string.diagnostics_device) {
        Line(R.string.diagnostics_app, report.device.appVersion)
        Line(R.string.diagnostics_android, report.device.androidVersion)
        Line(R.string.diagnostics_build, report.device.fireOsBuild)
        Line(R.string.diagnostics_model, report.device.model)
    }
}

@Composable
private fun DatabasePart(context: Context, report: DiagnosticsReport) {
    Part(R.string.diagnostics_database) {
        Gathered(report.database) { db ->
            Line(R.string.diagnostics_files, context.size(db.fileBytes))
            Line(R.string.diagnostics_hand_marks, db.handMarkedPositions.toString())
            Line(R.string.diagnostics_removed_favourites, db.removedFavourites.toString())
            db.tables.forEach { table ->
                val failure = table.failure
                if (table.rows != null) Plain(table.name, table.rows.toString()) else Failure(table.name, failure)
            }
        }
    }
}

@Composable
private fun StoragePart(context: Context, report: DiagnosticsReport) {
    Part(R.string.diagnostics_storage) {
        Gathered(report.storage) { s ->
            Line(
                R.string.diagnostics_free,
                context.getString(
                    R.string.diagnostics_storage_value,
                    context.size(s.freeBytes),
                    context.size(s.totalBytes),
                ),
            )
            Line(R.string.diagnostics_image_cache, context.size(s.imageCacheBytes))
            Line(R.string.diagnostics_subtitles, context.size(s.subtitlesBytes))
            Line(R.string.diagnostics_updates, context.size(s.updatesBytes))
            Line(
                R.string.diagnostics_temp_files,
                context.getString(
                    R.string.diagnostics_temp_files_value,
                    s.looseCacheFiles,
                    context.size(s.looseCacheBytes),
                ),
            )
        }
    }
}

@Composable
private fun MemoryPart(context: Context, report: DiagnosticsReport) {
    Part(R.string.diagnostics_memory) {
        Gathered(report.memory) { memory ->
            Line(
                R.string.diagnostics_heap,
                context.getString(
                    R.string.diagnostics_memory_value,
                    context.size(memory.heapUsedBytes),
                    context.size(memory.heapMaxBytes),
                ),
            )
            Line(R.string.diagnostics_pss, context.size(memory.pssBytes))
            Line(
                R.string.diagnostics_low_memory,
                stringResource(if (memory.lowMemory) R.string.diagnostics_yes else R.string.diagnostics_no),
            )
        }
    }
}

@Composable
private fun OpensPart(context: Context, report: DiagnosticsReport) {
    Part(R.string.diagnostics_opens) {
        Gathered(report.opens) { opens ->
            if (opens.isEmpty()) Quiet(stringResource(R.string.diagnostics_none_yet))
            opens.asReversed().forEach { open ->
                val outcome = if (open.failure ==
                    null
                ) {
                    R.string.diagnostics_open_played
                } else {
                    R.string.diagnostics_open_failed
                }
                Plain(
                    "${context.time(open.atUtcMillis)}  ${stringResource(kindName(open.kind))}",
                    stringResource(outcome, context.seconds(open.millis)),
                )
                open.failure?.let { Quiet(it) }
            }
        }
    }
}

@Composable
private fun ErrorsPart(context: Context, report: DiagnosticsReport) {
    Part(R.string.diagnostics_errors) {
        Gathered(report.errors) { errors ->
            if (errors.isEmpty()) Quiet(stringResource(R.string.diagnostics_none_yet))
            errors.asReversed().forEach { error ->
                val times = if (error.count > 1) "  " + stringResource(R.string.diagnostics_times, error.count) else ""
                Quiet(context.time(error.atUtcMillis) + times)
                ErrorMessage(error.sentence, error.detail, textAlign = TextAlign.Start)
            }
        }
    }
}
