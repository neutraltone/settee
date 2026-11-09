/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv.data.repo

import app.opentv.data.model.Channel
import app.opentv.player.playsQualityRanked

/**
 * The Qualities of [channel] a recording may use: the ones this device can play, so a viewer can always
 * watch what they recorded. Goes by their tags, as [LineupChannel.primary] does. With none left it's
 * whatever plays by default, since a tag can be only branding.
 */
fun ChannelLineup.recordable(channel: LineupChannel): List<Channel> =
    channel.qualities.filter { decoders.playsQualityRanked(it.qualityRank) }.ifEmpty { listOf(channel.primary) }

/** What to record of [channel] with [playing] on screen: that Quality, unless this device can't play it. */
fun ChannelLineup.toRecord(channel: LineupChannel, playing: Channel = channel.primary): Channel {
    val recordable = recordable(channel)
    return recordable.firstOrNull { it.id == playing.id } ?: recordable.first()
}
