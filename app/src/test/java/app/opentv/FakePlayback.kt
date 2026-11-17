/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv

import app.opentv.data.model.Episode
import app.opentv.data.repo.MediaKey
import app.opentv.player.NextEpisodeCountdown
import app.opentv.player.PlayerController
import app.opentv.ui.vod.VodPlayerViewModel

/** How long every episode [FakePlayback] plays is. */
const val EPISODE_MILLIS = 2_400_000L

/** Stands in for the player screen's PlayerController: plays whatever it is handed, wherever it is told to be. */
class FakePlayback : VodPlayerViewModel.Playback {
    var positionMillis = 0L
    var durationMillis = 0L
    var paused = false
    var stopped = false

    // Synchronised: a move can finish on the thread the database answered on, while a test reads it.
    val played: MutableList<Pair<MediaKey, Long>> = java.util.Collections.synchronizedList(mutableListOf())
    var steppedDownTo: MediaKey? = null

    var ended = false

    /** Whether the stream last [play]ed is still being found, as on a slow portal. */
    var switching = false
    private var askedFrom = 0L

    // As PlayerController's adapter reports it: see vodPlayback, which VodPlaybackTest checks.
    override fun progress() = if (switching) {
        NextEpisodeCountdown.Playback(positionMillis = 0, durationMillis = 0)
    } else {
        NextEpisodeCountdown.Playback(positionMillis, durationMillis, ended)
    }

    override fun position() = if (switching) askedFrom.takeIf { it > 0 } else positionMillis

    override fun steppedDownTo() = steppedDownTo

    override fun play(request: PlayerController.Request, skipped: String?) {
        played += request.key to request.startPositionMillis
        askedFrom = request.startPositionMillis
        positionMillis = request.startPositionMillis
        durationMillis = EPISODE_MILLIS
        paused = false
    }

    override fun pause() {
        paused = true
    }

    override fun seekTo(positionMillis: Long) {
        this.positionMillis = positionMillis
    }

    override fun stop() {
        stopped = true
    }
}

/** What the player is handed to play [this] episode. */
val Episode.key get() = MediaKey.Episode(id)

/** Plays what [playback] has on through the Next Episode card to its end, polling as the screen does. */
suspend fun VodPlayerViewModel.playToEnd(playback: FakePlayback) {
    // Inside the countdown, which with no timings runs from 30 to 15 seconds before the end.
    playback.positionMillis = EPISODE_MILLIS - 20_000
    poll()
    playback.positionMillis = EPISODE_MILLIS
    poll()
}
