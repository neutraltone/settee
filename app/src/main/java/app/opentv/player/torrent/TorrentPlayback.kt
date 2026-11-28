/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv.player.torrent

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * One player's use of the app's torrent [engine] (#670), or of none: the player says what it plays next
 * with [play], and this starts, keeps or stops the torrent. The engine is the app's, so a player that never
 * started a torrent never stops one.
 */
class TorrentPlayback(private val engine: TorrentEngine?) {
    /** Where the torrent has got to; [TorrentStatus.Idle] for a player with no engine. */
    val status: StateFlow<TorrentStatus> = engine?.status ?: MutableStateFlow(TorrentStatus.Idle)

    /** The torrent this player started the engine on, until it stops it. */
    private var started: TorrentRequest? = null

    /**
     * Runs [request] on the engine, or stops the torrent this player started when it's null, which deletes
     * its pieces. One already running carries on, so a restart doesn't throw its pieces away; one that
     * failed starts afresh.
     */
    fun play(request: TorrentRequest?) {
        val engine = engine ?: return
        val now = engine.status.value
        val running = now !is TorrentStatus.Failed && now != TorrentStatus.Idle
        when {
            request == null -> if (started != null) engine.stop()
            request == started && running -> Unit
            else -> engine.start(request)
        }
        started = request
    }
}
