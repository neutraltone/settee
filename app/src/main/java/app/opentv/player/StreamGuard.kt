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
import app.opentv.data.repo.RecordedOn
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
 * first; without one, calls [onCantPlay] with the reason and whether a Retry could help. A stream that
 * never starts at all goes straight to [onCantPlay] (see [StartUpWatch]).
 */
// One function for each thing that can happen to a stream, which the controller tells it about.
@Suppress("TooManyFunctions")
@OptIn(UnstableApi::class)
internal class StreamGuard(
    private val context: Context,
    private val scope: CoroutineScope,
    private val decoders: DecoderList,
    /** Moves to the next Quality, if there is one, and says what from and to. [forSound]: only sound is wrong. */
    private val moveDown: (forSound: Boolean) -> Step?,
    /** Picks "this TV" over "this device" in what [onCantPlay] is given and in the notices. */
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

    /**
     * Whether the stream on the player has started playing. A notice said before then is kept up until
     * it has: a torrent knows its tracks long before its first frame, and a notice timed from then was
     * gone behind Loading before the viewer saw a picture.
     */
    private var playing = true
    private var timeNoticeOnArrival = false
    private val loggedTracks = HashSet<String>()

    private val firstFrame = FirstFrameWatch(scope) { format ->
        cantPlay(PlaybackErrors.noFirstFrame(context, format, onTelevision), canRetry = true)
    }

    /** Where the stream on the player is kept, if it's a finished recording, for the start-up limit's sentence. */
    private var recordedOn: RecordedOn? = null

    // Straight to the sentence: a panel that sends nothing usually does so on every Quality, so stepping
    // down would only make the viewer wait the limit again for each. Try again is theirs to choose.
    private val startUp =
        StartUpWatch(scope) {
            giveUp(PlaybackErrors.describe(context, PlaybackErrors.Stopped.START_UP_LIMIT, recordedOn), canRetry = true)
        }

    /** What the player reports its decoders to, for the first-frame limit. */
    val firstFrameListener: AnalyticsListener get() = firstFrame

    /** The stream playing is on its way out, however late its first frame is. */
    fun leaving() {
        firstFrame.newStream()
        startUp.end()
    }

    /**
     * A stream has just been handed to the player. [limitStartUp] gives up on it if it never starts (see
     * [StartUpWatch]); not for a recording still being written, which waits on the recording growing,
     * from a local file or the viewer's NAS, where the timed-out sentence blames a provider that isn't there.
     * [recordedOn] is where a finished recording is kept, null for anything else.
     */
    fun started(limitStartUp: Boolean, recordedOn: RecordedOn? = null) {
        this.recordedOn = recordedOn
        firstFrame.newStream()
        if (limitStartUp) startUp.newStream()
        loggedTracks.clear()
        playing = false
    }

    /**
     * The stream on the player is ready to play, for the first time or again, or has already ended:
     * either way it got somewhere, so it isn't the start-up limit's business any more.
     */
    fun arrived() {
        startUp.end()
        playing = true
        if (timeNoticeOnArrival) {
            timeNoticeOnArrival = false
            timeNotice()
        }
    }

    /** Whether the controller is holding playback back on purpose, which the start-up limit doesn't count. */
    var held: Boolean by startUp::held

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
        val sentence = when {
            from.isEmpty() -> hardware(
                onTelevision,
                R.string.player_stepped_down_unnamed_tv,
                R.string.player_stepped_down_unnamed_device,
            )
            to.label.isEmpty() -> hardware(
                onTelevision,
                R.string.player_stepped_down_lower_tv,
                R.string.player_stepped_down_lower_device,
            )
            else -> hardware(onTelevision, R.string.player_stepped_down_tv, R.string.player_stepped_down_device)
        }
        say(context.getString(sentence, from, to.label))
    }

    /** The viewer has moved on to something else, so what was said about the last one no longer holds. */
    fun forgetNotices() {
        stopSaying()
        _steppedDownTo.value = null
        saidNoSound = false
    }

    fun release() {
        leaving()
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
            giveUp(reason, canRetry)
        } else {
            steppedDown(step.from, step.to)
        }
    }

    /**
     * Stops watching the stream before saying why it can't play. The controller only stops the player, so
     * a limit still counting would fire later and cover the sentence with its own, or step down a Quality
     * behind it: a decoder that started late on a stream the start-up limit gave up on, say.
     */
    private fun giveUp(reason: ViewerError, canRetry: Boolean) {
        leaving()
        stopSaying()
        onCantPlay(reason, canRetry)
    }

    /** Takes down what's showing: it would only contradict what's about to be said. */
    private fun stopSaying() {
        noticeJob?.cancel()
        timeNoticeOnArrival = false
        _notice.value = null
    }

    private fun say(message: String) {
        noticeJob?.cancel()
        _notice.value = message
        if (playing) timeNotice() else timeNoticeOnArrival = true
    }

    private fun timeNotice() {
        noticeJob?.cancel()
        noticeJob = scope.launch {
            delay(NOTICE_MILLIS)
            _notice.value = null
        }
    }

    private companion object {
        const val NOTICE_MILLIS = 5_000L
    }
}
