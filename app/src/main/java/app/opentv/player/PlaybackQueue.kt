/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv.player

/**
 * The full-screen player's channel list, in the order channel up and down step through it. The
 * order itself is [app.opentv.data.repo.ChannelLineup.zapChannels]'s; this is what the player draws.
 */
object PlaybackQueue {
    /** One Channel in the list: [id] is the Quality it plays, [qualityIds] every Quality it covers. */
    data class Item(
        val id: Long,
        val name: String,
        val logoUrl: String?,
        val number: Int? = null,
        val qualityIds: Set<Long> = setOf(id),
    )
}
