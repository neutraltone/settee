/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv

import app.opentv.data.model.Episode
import app.opentv.data.repo.MediaKey
import app.opentv.player.NextEpisodeCountdown
import app.opentv.ui.vod.VodPlayerViewModel

/** How long every episode [FakePlayback] plays is. */
const val EPISODE_MILLIS = 2_400_000L

/** Stands in for the player screen's PlayerController: plays whatever it is handed, wherever it is told to be. */
class FakePlayback : VodPlayerViewModel.Playback {
    var positionMillis = 0L
    var durationMillis = 0L
    var paused = false
    var stopped = false
    val played = mutableListOf<Pair<MediaKey, Long>>()

    override fun progress() = NextEpisodeCountdown.Playback(positionMillis, durationMillis)

    override fun play(key: MediaKey, startPositionMillis: Long) {
        played += key to startPositionMillis
        positionMillis = startPositionMillis
        durationMillis = EPISODE_MILLIS
        paused = false
    }

    override fun pause() {
        paused = true
    }

    override fun stop() {
        stopped = true
    }
}

/** What the player is handed to play [this] episode. */
val Episode.key get() = MediaKey.Episode(id)

/** Plays what [playback] has on through the Next Episode card to its end, polling as the screen does. */
suspend fun VodPlayerViewModel.playToEnd(playback: FakePlayback) {
    playback.positionMillis = EPISODE_MILLIS - 10_000
    poll()
    playback.positionMillis = EPISODE_MILLIS
    poll()
}
