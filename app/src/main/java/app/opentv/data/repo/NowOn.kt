/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv.data.repo

import kotlinx.coroutines.flow.first

/**
 * The title of what's on now on each of [channels], by [LineupChannel.key], from the guide stored on
 * the device, each Channel's guide picked as [ChannelLineup.guideId] picks it; a Channel with nothing
 * on is left out. Two reads however many Channels: asking one Channel at a time cost Home a few
 * queries per Favourite every minute.
 */
suspend fun ChannelLineup.nowOn(channels: List<LineupChannel>, nowMillis: Long): Map<String, String> {
    val stored = if (channels.isEmpty()) emptySet() else programmes.channelIdsWithProgrammes().toHashSet()
    val guides = channels.mapNotNull { channel -> channel.guideId(stored::contains)?.let { channel.key to it } }
    if (guides.isEmpty()) return emptyMap()
    val onNow = programmes.observeNow(nowMillis).first()
        .groupBy { it.epgChannelId }
        .mapValues { (_, shows) -> shows.minBy { it.startUtcMillis } }
    return guides.mapNotNull { (key, guide) -> onNow[guide]?.let { key to it.title } }.toMap()
}
