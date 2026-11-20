/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv.ui.settings

import android.content.Context
import android.text.format.Formatter
import app.opentv.R
import app.opentv.core.ViewerError
import app.opentv.diagnostics.DiagnosticsReport
import app.opentv.diagnostics.Gathered
import app.opentv.diagnostics.MILLIS_PER_SECOND
import app.opentv.diagnostics.fold

/*
 * The gist of each part of a report, for the note on its Diagnostics row, and the sizes and times the
 * full report shares with it, worded in the device's language.
 */

/** A row's note: what was gathered, or why it couldn't be. */
internal class DiagnosticsSummary(val text: String, val failure: ViewerError? = null)

private fun <T> Gathered<T>.summary(said: (T) -> String): DiagnosticsSummary =
    fold(got = { DiagnosticsSummary(said(it)) }, failed = { DiagnosticsSummary(it.sentence, it) })

internal fun Context.size(bytes: Long): String = Formatter.formatShortFileSize(this, bytes)

internal fun databaseSummary(context: Context, report: DiagnosticsReport) = report.database.summary { db ->
    val rows = db.tables.sumOf { it.rows ?: 0L }
    context.getString(R.string.diagnostics_database_value, context.size(db.fileBytes), rows)
}

internal fun storageSummary(context: Context, report: DiagnosticsReport) = report.storage.summary { s ->
    context.getString(R.string.diagnostics_storage_value, context.size(s.freeBytes), context.size(s.totalBytes))
}

internal fun memorySummary(context: Context, report: DiagnosticsReport) = report.memory.summary { m ->
    context.getString(R.string.diagnostics_memory_value, context.size(m.heapUsedBytes), context.size(m.heapMaxBytes))
}

internal fun opensSummary(context: Context, report: DiagnosticsReport) = report.opens.summary { opens ->
    val last = opens.lastOrNull() ?: return@summary context.getString(R.string.diagnostics_none_yet)
    val failed = opens.count { it.failure != null }
    // How long a failure took says nothing about how fast things open, so it's called a failure.
    val value = if (last.failure == null) R.string.diagnostics_opens_value else R.string.diagnostics_opens_failed_value
    context.getString(value, context.seconds(last.millis), failed, opens.size)
}

internal fun errorsSummary(context: Context, report: DiagnosticsReport) = report.errors.summary { errors ->
    val last = errors.lastOrNull() ?: return@summary context.getString(R.string.diagnostics_none_yet)
    last.sentence
}

internal fun Context.seconds(millis: Long): String = getString(R.string.diagnostics_seconds, millis / MILLIS_PER_SECOND)
