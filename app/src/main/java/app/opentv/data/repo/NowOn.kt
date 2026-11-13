/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv.data.repo

/**
 * The longest programme [nowOn] finds: the on-now query only reads programmes ending within this of
 * now, which keeps it to a short stretch of the index. A day covers the longest a guide realistically
 * lists (all-day cricket, a 24-hour marathon); anything longer that's on now goes missing.
 */
private const val LONGEST_PROGRAMME_MILLIS = 24 * 60 * 60 * 1_000L

/**
 * The title of what's on now on each of [channels], by [LineupChannel.key], from the guide stored on
 * the device, each Channel's guide picked as [ChannelLineup.guideId] picks it; a Channel with nothing
 * on is left out, and of two programmes on at once the one that started first wins. One read however
 * many Channels: asking one Channel at a time cost Home a few queries per Favourite every minute.
 */
suspend fun ChannelLineup.nowOn(channels: List<LineupChannel>, nowMillis: Long): Map<String, String> {
    val stored = if (channels.isEmpty()) emptySet() else storedGuides.ids()
    val guides = channels.mapNotNull { channel -> channel.guideId(stored::contains)?.let { channel.key to it } }
    if (guides.isEmpty()) return emptyMap()
    val onNow = programmes.onNow(nowMillis, nowMillis + LONGEST_PROGRAMME_MILLIS)
        .groupBy { it.epgChannelId }
        .mapValues { (_, shows) -> shows.minBy { it.startUtcMillis } }
    return guides.mapNotNull { (key, guide) -> onNow[guide]?.let { key to it.title } }.toMap()
}
