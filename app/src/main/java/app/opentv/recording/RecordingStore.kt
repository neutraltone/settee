/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv.recording

/** Where a recording's file goes, as far as room goes: this device, the USB drive, or a NAS share. */
sealed interface RecordingStore {
    data object Device : RecordingStore

    data object Usb : RecordingStore

    /** One share on a NAS, by its host and name. */
    data class Nas(val host: String, val share: String) : RecordingStore

    companion object {
        /** The storage a recording at [locator] is on: see [RecordingStorage] for its three shapes. */
        fun of(locator: String): RecordingStore = when {
            SmbClient.isSmb(locator) -> SmbClient.parse(locator).let { Nas(it.host, it.share) }
            RecordingStorage.isContent(locator) || RecordingStorage.isUsbPlaceholder(locator) -> Usb
            else -> Device
        }
    }
}
