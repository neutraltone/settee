/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv.recording

import android.content.Context
import android.os.StatFs
import android.provider.DocumentsContract
import androidx.core.net.toUri
import app.opentv.core.AppSettings

/**
 * How many bytes each recording storage has free, read as each tells it, or null where it can't be
 * told: a NAS that can't be reached, or a drive that doesn't say. Blocks on disk or the network, so
 * never call it on the main thread.
 */
class FreeSpace(private val context: Context, private val settings: AppSettings) {

    fun of(store: RecordingStore): Long? = runCatching {
        when (store) {
            // As Recording settings' storage note reads it.
            RecordingStore.Device -> StatFs(RecordingStorage.internalDir(context).path).availableBytes
            RecordingStore.Usb -> usb()
            is RecordingStore.Nas ->
                SmbClient.freeSpace(RecordingStorage.smbConfig(settings).copy(host = store.host, share = store.share))
        }
    }.getOrNull()

    /**
     * What the documents provider says is free on the root the USB folder is on. It answers for its
     * roots, not for a folder, so the folder's root is the one whose document is the folder or holds it.
     */
    private fun usb(): Long? {
        val tree = settings.usbTreeUri.value?.toUri() ?: return null
        val folder = DocumentsContract.getTreeDocumentId(tree)
        val roots = DocumentsContract.buildRootsUri(tree.authority)
        val columns = arrayOf(DocumentsContract.Root.COLUMN_DOCUMENT_ID, DocumentsContract.Root.COLUMN_AVAILABLE_BYTES)
        return context.contentResolver.query(roots, columns, null, null, null)?.use { rows ->
            generateSequence { if (rows.moveToNext()) rows else null }
                .filter { !it.isNull(1) && it.getLong(1) >= 0 && folder.startsWith(it.getString(0)) }
                .map { it.getString(0).length to it.getLong(1) }
                .toList()
                // The closest root, should one provider's roots sit inside each other.
                .maxByOrNull { it.first }
                ?.second
        }
    }
}
