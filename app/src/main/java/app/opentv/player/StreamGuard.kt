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
import app.opentv.core.ViewerError
import app.opentv.core.hardware
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
 * can't take, a decoder that shows nothing, sound nobody will hear. Asks [moveDown] for another Quality
 * first; without one, calls [onCantPlay] with the reason and whether a Retry could help.
 */
@OptIn(UnstableApi::class)
internal class StreamGuard(
    private val context: Context,
    private val scope: CoroutineScope,
    private val decoders: DecoderList,
    /** Moves to the next Quality, if there is one, and says what from and to. [forSound]: only sound is wrong. */
    private val moveDown: (forSound: Boolean) -> Step?,
    /** Picks "this TV" over "this device" in what [onCantPlay] is given. */
    private val onTelevision: Boolean,
    private val onCantPlay: (reason: ViewerError, canRetry: Boolean) -> Unit,
) {
    /** A move from the Quality called [from] to [to]. */
    data class Step(val from: String, val to: PlayerController.Quality)

    private val _notice = MutableStateFlow<String?>(null)
    val notice: StateFlow<String?> = _notice.asStateFlow()
    private var noticeJob: Job? = null

    private val _steppedDownTo = MutableStateFlow<MediaKey?>(null)
    val steppedDownTo: StateFlow<MediaKey?> = _steppedDownTo.asStateFlow()

    private var saidNoSound = false
    private val loggedTracks = HashSet<String>()

    private val firstFrame = FirstFrameWatch(scope) { format ->
        cantPlay(PlaybackErrors.noFirstFrame(context, format, onTelevision), canRetry = true)
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
            cantPlay(PlaybackErrors.tooBig(context, refused, onTelevision), canRetry = false)
        } else if (soundWanted) {
            sayIfNoSound(tracks)
        }
    }

    /** Says the stream moved from the Quality called [from] to [to], and holds on to where it went. */
    fun steppedDown(from: String, to: PlayerController.Quality) {
        _steppedDownTo.value = to.key
        say(
            when {
                from.isEmpty() -> context.getString(R.string.player_stepped_down_unnamed)
                to.label.isEmpty() -> context.getString(R.string.player_stepped_down_lower, from)
                else -> context.getString(R.string.player_stepped_down, from, to.label)
            },
        )
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
        val audio = tracks.groups.filter { it.type == C.TRACK_TYPE_AUDIO }
        if (saidNoSound || audio.isEmpty() || audio.any { it.isSupported(true) }) return
        val format = audio.first().getTrackFormat(0).displayName
        val step = moveDown(true)
        if (step == null) {
            saidNoSound = true
            say(
                context.getString(
                    hardware(onTelevision, R.string.player_no_sound_tv, R.string.player_no_sound_device),
                    format,
                ),
            )
        } else {
            _steppedDownTo.value = step.to.key
            val sentence = hardware(
                onTelevision,
                R.string.player_stepped_down_for_sound_tv,
                R.string.player_stepped_down_for_sound_device,
            )
            say(context.getString(sentence, format))
        }
    }

    private fun cantPlay(reason: ViewerError, canRetry: Boolean) {
        val step = moveDown(false)
        if (step == null) {
            stopSaying()
            onCantPlay(reason, canRetry)
        } else {
            steppedDown(step.from, step.to)
        }
    }

    /** Takes down what's showing: it would only contradict what's about to be said. */
    private fun stopSaying() {
        noticeJob?.cancel()
        _notice.value = null
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
