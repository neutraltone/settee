/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv.diagnostics

import app.opentv.core.ViewerError
import app.opentv.sync.NasFolder
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale

/*
 * The report as plain text, for the NAS. In English whatever the app's language: it's read by
 * whoever is working out what went wrong, not by the viewer, and one language keeps reports comparable.
 */

/** Where an export made at [madeAtUtcMillis] goes, within the NAS folder sync uses: one file per report. */
fun diagnosticsFileName(deviceId: String, madeAtUtcMillis: Long): String =
    "${NasFolder.NAME}/diagnostics/$deviceId-${FILE_TIME.format(Instant.ofEpochMilli(madeAtUtcMillis))}.txt"

/** The report, redacted once more as it leaves the device, whatever each part already did. */
fun DiagnosticsReport.asText(madeAtUtcMillis: Long): String = redact(
    buildString {
        appendLine("Settee diagnostics")
        appendLine("Made ${MADE_AT.format(Instant.ofEpochMilli(madeAtUtcMillis))} UTC")
        section("Device") {
            appendLine("App: Settee ${device.appVersion}")
            appendLine("Android: ${device.androidVersion}")
            appendLine("Build: ${device.fireOsBuild}")
            appendLine("Model: ${device.model}")
        }
        section("Database") {
            gathered(database) { db ->
                appendLine("Files: ${bytes(db.fileBytes)} (with -wal and -shm)")
                appendLine("Positions marked by hand: ${db.handMarkedPositions}")
                appendLine("Favourites marked removed: ${db.removedFavourites}")
                db.tables.forEach { table ->
                    val rows = table.rows?.let { if (it == 1L) "1 row" else "$it rows" }
                    appendLine("${table.name}: ${rows ?: failure(table.failure)}")
                }
            }
        }
        section("Storage") {
            gathered(storage) { s ->
                appendLine("Free: ${bytes(s.freeBytes)} of ${bytes(s.totalBytes)}")
                appendLine("Image cache: ${bytes(s.imageCacheBytes)}")
                appendLine("Subtitles: ${bytes(s.subtitlesBytes)}")
                appendLine("Updates: ${bytes(s.updatesBytes)}")
                appendLine("Temp files in the cache: ${s.looseCacheFiles}, ${bytes(s.looseCacheBytes)}")
            }
        }
        section("Memory") {
            gathered(memory) { m ->
                appendLine("Heap: ${bytes(m.heapUsedBytes)} of ${bytes(m.heapMaxBytes)}")
                appendLine("PSS: ${bytes(m.pssBytes)}")
                appendLine("System low on memory: ${if (m.lowMemory) "yes" else "no"}")
            }
        }
        section("Opens") { gathered(opens) { appendOpens(it) } }
        section("Errors") { gathered(errors) { appendErrors(it) } }
    },
)

private fun StringBuilder.appendOpens(opens: List<LoggedOpen>) {
    if (opens.isEmpty()) appendLine("None yet")
    opens.forEach { open ->
        val outcome = open.failure?.let { "failed after ${seconds(open.millis)} · $it" }
            ?: "played after ${seconds(open.millis)}"
        appendLine("${LIST_TIME.format(Instant.ofEpochMilli(open.atUtcMillis))} ${open.kind} · $outcome")
    }
}

private fun StringBuilder.appendErrors(errors: List<LoggedError>) {
    if (errors.isEmpty()) appendLine("None yet")
    errors.forEach { error ->
        val times = if (error.count > 1) " ×${error.count}" else ""
        val at = LIST_TIME.format(Instant.ofEpochMilli(error.atUtcMillis))
        appendLine("$at ${failure(ViewerError(error.sentence, error.detail))}$times")
    }
}

private inline fun StringBuilder.section(title: String, body: StringBuilder.() -> Unit) {
    appendLine()
    appendLine(title)
    body()
}

private fun <T> StringBuilder.gathered(part: Gathered<T>, body: StringBuilder.(T) -> Unit) {
    part.fold(got = { body(it) }, failed = { appendLine(failure(it)) })
}

private fun failure(error: ViewerError?): String = when {
    error == null -> "unknown"
    error.detail.isBlank() -> error.sentence
    else -> "${error.sentence} (${error.detail})"
}

private fun seconds(millis: Long): String = String.format(Locale.ROOT, "%.1f s", millis / MILLIS_PER_SECOND)

private fun bytes(count: Long): String = when {
    count >= MIB -> String.format(Locale.ROOT, "%.1f MB", count / MIB.toDouble())
    count >= KIB -> String.format(Locale.ROOT, "%.1f KB", count / KIB.toDouble())
    else -> "$count B"
}

private const val KIB = BYTES_PER_KIB
private const val MIB = KIB * KIB

private val MADE_AT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm", Locale.ROOT).withZone(ZoneOffset.UTC)
private val LIST_TIME = DateTimeFormatter.ofPattern("MM-dd HH:mm:ss", Locale.ROOT).withZone(ZoneOffset.UTC)
private val FILE_TIME = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'", Locale.ROOT).withZone(ZoneOffset.UTC)
