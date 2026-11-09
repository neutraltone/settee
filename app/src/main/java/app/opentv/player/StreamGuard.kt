/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv.player

import android.content.Context
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.Tracks
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.analytics.AnalyticsListener
import app.opentv.R
import app.opentv.data.repo.MediaKey
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Watches one [PlayerController]'s streams for what no error would ever report: a video this device
 * can't take, a decoder that shows nothing, sound nobody will hear. Calls [onCantPlay] with the
 * sentence for a video that can't go on, and whether a Retry could help.
 */
@OptIn(UnstableApi::class)
internal class StreamGuard(
    private val context: Context,
    private val scope: CoroutineScope,
    private val decoders: DecoderList,
    private val onCantPlay: (sentence: String, canRetry: Boolean) -> Unit,
) {
    private val _notice = MutableStateFlow<String?>(null)
    val notice: StateFlow<String?> = _notice.asStateFlow()
    private var noticeJob: Job? = null

    private val _steppedDownTo = MutableStateFlow<MediaKey?>(null)
    val steppedDownTo: StateFlow<MediaKey?> = _steppedDownTo.asStateFlow()

    private var saidNoSound = false
    private val loggedTracks = HashSet<String>()

    private val firstFrame = FirstFrameWatch(scope) { format ->
        onCantPlay(PlaybackErrors.noFirstFrame(context, format), true)
    }

    /** What the player reports its decoders to, for the first-frame limit. */
    val firstFrameListener: AnalyticsListener get() = firstFrame

    /** The stream playing is on its way out, however late its first frame is. */
    fun leaving() = firstFrame.newStream()

    /** A stream has just been handed to the player. */
    fun started() {
        firstFrame.newStream()
        loggedTracks.clear()
    }

    fun tracksChanged(tracks: Tracks, soundWanted: Boolean) {
        DecoderLog.logTracks(tracks, decoders, loggedTracks)
        val refused = decoders.refusedVideoOf(tracks)
        if (refused != null) {
            onCantPlay(PlaybackErrors.tooBig(context, refused), false)
        } else if (soundWanted) {
            sayIfNoSound(tracks)
        }
    }

    /** Says the stream moved from the Quality called [from] to [to], and holds on to where it went. */
    fun steppedDown(from: String, to: PlayerController.Quality) {
        _steppedDownTo.value = to.key
        say(
            if (from.isEmpty() || to.label.isEmpty()) {
                context.getString(R.string.player_stepped_down_unnamed)
            } else {
                context.getString(R.string.player_stepped_down, from, to.label)
            },
        )
    }

    /** Takes down what's showing: it would only contradict what's about to be said. */
    fun stopSaying() {
        noticeJob?.cancel()
        _notice.value = null
    }

    /** The viewer has moved on to something else, so what was said about the last one no longer holds. */
    fun forgetNotices() {
        stopSaying()
        _steppedDownTo.value = null
        saidNoSound = false
    }

    fun release() {
        firstFrame.newStream()
        noticeJob?.cancel()
    }

    /** Once a stream, when none of its sound can play or pass through. No audio at all isn't a fault. */
    private fun sayIfNoSound(tracks: Tracks) {
        if (saidNoSound) return
        val audio = tracks.groups.filter { it.type == C.TRACK_TYPE_AUDIO }
        if (audio.isEmpty() || audio.any { it.isSupported(true) }) return
        saidNoSound = true
        say(context.getString(R.string.player_no_sound, audio.first().getTrackFormat(0).displayName))
    }

    private fun say(message: String) {
        noticeJob?.cancel()
        _notice.value = message
        noticeJob = scope.launch {
            delay(NOTICE_MILLIS)
            _notice.value = null
        }
    }

    private companion object {
        const val NOTICE_MILLIS = 5_000L
    }
}
