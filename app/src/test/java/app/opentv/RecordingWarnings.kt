/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv

import app.opentv.core.RecordingSignals

/** The booking the recording-switch warning tests warn about. */
const val WARNED_BOOKING = 7L

/** Warns, as the recording alarm does 30 seconds ahead, that [recordingId] is about to take over the screen. */
fun warnOfRecording(recordingId: Long = WARNED_BOOKING, title: String = "The News") {
    RecordingSignals.warn(
        RecordingSignals.Imminent(
            recordingId = recordingId,
            title = title,
            // Named after no Channel the tests list, so it can't pass for one the player shows.
            channelName = "Delta Four",
            startAtMillis = System.currentTimeMillis() + 30_000L,
        ),
    )
}

/** Whether the screen switches to [recordingId] when it starts recording, as the capture asks it to. */
fun switchesWhenRecordingStarts(recordingId: Long = WARNED_BOOKING): Boolean {
    RecordingSignals.requestWatch(recordingId, System.currentTimeMillis())
    return RecordingSignals.watch.value?.recordingId == recordingId
}
