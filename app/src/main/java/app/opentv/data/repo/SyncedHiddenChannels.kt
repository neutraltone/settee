/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv.data.repo

import app.opentv.data.db.OpenTvDatabase
import app.opentv.data.model.ChannelHiddenChange
import app.opentv.sync.SyncHiddenChannel

/**
 * Hidden channels as they travel between devices: by source name and the provider's stream id (ADR
 * 0004), each hide or unhide dated by when a viewer made it. Hiding is device-wide, so no Profile.
 */
class SyncedHiddenChannels(private val db: OpenTvDatabase) {
    private val channels = db.channels()

    /** The channels a viewer hid or showed on this device. */
    suspend fun export(): List<SyncHiddenChannel> = channels.hiddenForSync()

    /**
     * Merges another device's hides and unhides, newest change wins, each dated by [arrival]. A dated
     * change is kept, under the name its provider has here, even if the provider isn't listing that
     * channel right now. Returns how many channels it changed here.
     */
    suspend fun import(items: List<SyncHiddenChannel>, arrival: SyncArrival = SyncArrival.NONE): Int {
        val sources = arrival.sources(db.sources())
        return items.count { arrived ->
            val changedAt = arrival.clock.corrected(arrived.changedAtMillis)
            val source = sources.currentName(arrived.source)
            val sourceId = sources.id(source) ?: return@count false
            val here = channels.hiddenChange(source, arrived.id)?.changedAtMillis
            if (!isNewerChange(changedAt, here)) return@count false
            if (changedAt > 0) {
                channels.upsertHiddenChange(ChannelHiddenChange(source, arrived.id, arrived.hidden, changedAt))
            }
            val wasHidden = channels.isHidden(sourceId, arrived.id)
            channels.setHidden(sourceId, arrived.id, arrived.hidden)
            wasHidden != null && wasHidden != arrived.hidden
        }
    }
}
