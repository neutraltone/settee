/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv.recording

import android.content.Context
import androidx.core.net.toUri
import androidx.documentfile.provider.DocumentFile
import app.opentv.R
import app.opentv.core.AppSettings
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.OutputStream

/**
 * Where a recording's bytes go — and how to read the size back and delete it — hidden behind one
 * small surface so the engine, library and playback don't care whether a recording lives on the
 * box's internal storage, on a NAS over SMB, or on a plugged-in USB / external drive.
 *
 * ## Three shapes of locator
 * A recording's `filePath` (its stored locator) is one of:
 *  - an absolute filesystem path — an INTERNAL recording, played back via a `file://` URI;
 *  - an `smb://host/share/…` locator — a NAS recording, read back over SMB;
 *  - a `content://…` document URI — a USB recording written through the Storage Access Framework.
 *
 * USB is special because SAF only assigns a document's `content://` URI when the file is actually
 * created. So a USB recording is planned with a [USB_PLACEHOLDER_PREFIX]`filename` marker; when the
 * capture opens its sink the document is created, and the sink reports the real `content://` URI
 * back (via [Sink.resolvedLocator]) for the caller to persist onto the row.
 */
object RecordingStorage {

    /** Where recordings go isn't there to write to; [sentence] says what the viewer can do about it. */
    class Unavailable(@androidx.annotation.StringRes val sentence: Int) : IOException("Recording storage unavailable")

    /** Marker locator for a USB recording that hasn't been created (and given a URI) yet. */
    private const val USB_PLACEHOLDER_PREFIX = "usb://"

    /** An opened destination the capture writes to, plus how to close it. */
    class Sink(
        val output: OutputStream,
        /**
         * The real locator the bytes are going to when it differs from the planned one: for USB the
         * `content://` document URI, known only once the file is created; for INTERNAL and SMB a
         * suffixed name when the planned one was already taken (#354). The capture persists this
         * onto the recording row so playback and delete can find the file. Null when the planned
         * locator is the final one.
         */
        val resolvedLocator: String? = null,
        private val closer: () -> Unit,
    ) {
        fun close() = closer()
    }

    fun smbConfig(settings: AppSettings): SmbConfig = SmbConfig(
        host = settings.smbHost.value,
        share = settings.smbShare.value,
        folder = settings.smbFolder.value,
        username = settings.smbUser.value,
        password = settings.smbPassword.value,
    )

    /** Internal recordings dir on the device's own (app-private) external storage — no permission needed. */
    fun internalDir(context: Context): File = File(context.getExternalFilesDir(null), "recordings").apply { mkdirs() }

    /**
     * The locator a new recording named [filename] would be stored at, given the current target.
     * Computed up front so the DB row knows where its file lives before capture starts.
     */
    fun plannedLocator(context: Context, settings: AppSettings, filename: String): String =
        when (settings.recordingTarget.value) {
            AppSettings.RecordingTarget.SMB ->
                SmbClient.locator(smbConfig(settings), filename)
            AppSettings.RecordingTarget.USB ->
                // The real content:// URI isn't known until the SAF document is created at capture
                // time; carry the intended filename in a placeholder until then.
                USB_PLACEHOLDER_PREFIX + filename
            AppSettings.RecordingTarget.INTERNAL ->
                File(internalDir(context), filename).absolutePath
        }

    /** True for a `content://` locator — a USB recording created through SAF. */
    fun isContent(locator: String): Boolean = locator.startsWith("content://")

    /** True for the pre-creation USB marker — a planned USB recording with no document yet. */
    fun isUsbPlaceholder(locator: String): Boolean = locator.startsWith(USB_PLACEHOLDER_PREFIX)

    /**
     * Open the destination named by [locator] for writing. For a USB placeholder this creates the
     * SAF document and reports its real `content://` URI through [Sink.resolvedLocator]; the caller
     * persists that so the finished recording can be found again. Throws with a human-readable
     * message when the USB drive / folder grant is missing, rather than crashing the capture.
     */
    fun openSink(context: Context, settings: AppSettings, locator: String): Sink = when {
        SmbClient.isSmb(locator) -> {
            val handle = SmbClient.openNewForWrite(smbConfig(settings), locator)
            Sink(handle.output, resolvedLocator = handle.locator.takeIf { it != locator }) { handle.close() }
        }
        isUsbPlaceholder(locator) ->
            openUsbSink(context, settings, locator.removePrefix(USB_PLACEHOLDER_PREFIX))
        isContent(locator) -> {
            // A USB recording whose document already exists (e.g. re-opened): write into it.
            val uri = locator.toUri()
            val out = context.contentResolver.openOutputStream(uri, "w")
                ?: throw Unavailable(R.string.rec_error_usb_unavailable)
            Sink(out, resolvedLocator = locator) {
                runCatching {
                    out.flush()
                    out.close()
                }
            }
        }
        else -> {
            File(locator).parentFile?.mkdirs()
            // Never open a taken name truncating: it would be another recording's file (#354).
            // createNewFile claims a name atomically, so two captures starting together can't both get it.
            val file = candidateNames(locator).map(::File).firstOrNull { it.createNewFile() }
                ?: throw IOException("No free recording name")
            val out = FileOutputStream(file)
            Sink(out, resolvedLocator = file.path.takeIf { it != locator }) { runCatching { out.close() } }
        }
    }

    /**
     * The names a new recording at [path] tries in turn: [path] itself, then `… (2)`, `… (3)` before
     * its extension. Checked when the file is created, not when it's booked, because a booking made
     * days ahead, or another device writing to the same NAS folder, can take the name in between; and
     * a row id in the name wouldn't do, as each device sharing that folder numbers its rows separately.
     */
    internal fun candidateNames(path: String): Sequence<String> {
        val slash = path.lastIndexOfAny(charArrayOf('/', '\\'))
        val dot = path.lastIndexOf('.').takeIf { it > slash } ?: path.length
        val stem = path.substring(0, dot)
        val extension = path.substring(dot)
        return sequenceOf(path) + (2..MAX_NAMES).asSequence().map { "$stem ($it)$extension" }
    }

    /** Past this many recordings of one name in one minute something else is wrong; stop looking. */
    private const val MAX_NAMES = 999

    /**
     * Create the recording's file on the granted USB tree and open it for writing. The tree URI
     * carries a persisted read/write permission, so the created document stays readable for
     * playback across restarts with no storage permission.
     */
    private fun openUsbSink(context: Context, settings: AppSettings, filename: String): Sink {
        val treeUriString = settings.usbTreeUri.value
            ?: throw Unavailable(R.string.rec_error_usb_no_folder)
        val tree = DocumentFile.fromTreeUri(context, treeUriString.toUri())
        if (tree == null || !tree.canWrite()) {
            throw Unavailable(R.string.rec_error_usb_unavailable)
        }
        val doc = tree.createFile(mimeForFilename(filename), filename)
            ?: throw Unavailable(R.string.rec_error_usb_unavailable)
        val out = context.contentResolver.openOutputStream(doc.uri)
            ?: throw Unavailable(R.string.rec_error_usb_unavailable)
        return Sink(out, resolvedLocator = doc.uri.toString()) {
            runCatching {
                out.flush()
                out.close()
            }
        }
    }

    /** Recordings are MPEG-TS `.ts`; anything else falls back to a generic video type. */
    private fun mimeForFilename(filename: String): String =
        if (filename.endsWith(".ts", ignoreCase = true)) "video/mp2t" else "video/*"

    fun delete(context: Context, settings: AppSettings, locator: String) {
        when {
            SmbClient.isSmb(locator) -> runCatching { SmbClient.delete(smbConfig(settings), locator) }
            isContent(locator) ->
                runCatching { DocumentFile.fromSingleUri(context, locator.toUri())?.delete() }
            isUsbPlaceholder(locator) -> Unit // never created — nothing on disk to remove
            else -> runCatching { File(locator).delete() }
        }
    }

    /**
     * Current on-disk size, or -1 when it can't be cheaply known (SMB / USB — the DB tracks it
     * instead).
     */
    fun sizeOf(locator: String): Long =
        if (SmbClient.isSmb(locator) || isContent(locator) || isUsbPlaceholder(locator)) {
            -1L
        } else {
            File(locator).length()
        }

    /** A filesystem-safe recording filename: `Channel - 2026-08-02 1830.ts`. */
    fun fileNameFor(channelName: String, title: String, startMillis: Long): String {
        val stamp = android.text.format.DateFormat.format("yyyy-MM-dd HHmm", startMillis)
        val base = (if (title.isBlank() || title == channelName) channelName else "$channelName - $title")
        val safe = base.replace(Regex("[^A-Za-z0-9 _.-]"), "_").take(80).trim()
        return "$safe - $stamp.ts"
    }
}
