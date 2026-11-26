/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv.player.torrent

/**
 * The libtorrent session settings a [TorrentEngineConfig] becomes, kept apart from the library so
 * they can be checked without loading it. [LibtorrentEngine] copies each onto a settings pack.
 */
internal data class LibtorrentSettings(
    val uploadRateLimit: Int,
    val connectionsLimit: Int,
    val maxQueuedDiskBytes: Int,
    val sendBufferWatermark: Int,
    /** 0: a torrent never stays to seed. */
    val activeSeeds: Int,
    /**
     * Off: Settee doesn't ask the router to open a port to the box. Peers it connects out to are
     * enough to stream, and a TV box shouldn't punch holes in a home network without being asked.
     */
    val enableUpnp: Boolean,
    val enableNatPmp: Boolean,
    /** Off: no announcing the box to the local network. */
    val enableLsd: Boolean,
    /** On: most streams' peers come from the DHT, not from trackers. */
    val enableDht: Boolean,
) {
    companion object {
        /**
         * Splits [TorrentEngineConfig.memoryCapBytes] between the two buffers that grow with
         * traffic: three quarters for blocks waiting to be written (downloading dominates while
         * streaming), a quarter for blocks queued to send (upload is capped and small).
         */
        fun from(config: TorrentEngineConfig) = LibtorrentSettings(
            uploadRateLimit = config.uploadRateCapBytesPerSecond,
            connectionsLimit = config.connectionsLimit,
            maxQueuedDiskBytes = config.memoryCapBytes / 4 * 3,
            sendBufferWatermark = config.memoryCapBytes / 4,
            activeSeeds = 0,
            enableUpnp = false,
            enableNatPmp = false,
            enableLsd = false,
            enableDht = true,
        )
    }
}
