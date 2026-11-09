/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv.ui.options

import android.content.Context
import app.opentv.core.ServiceLocator
import app.opentv.data.model.Programme
import app.opentv.data.model.Recording
import app.opentv.data.repo.LineupChannel
import app.opentv.data.repo.MediaKey
import app.opentv.ui.ChannelsViewModel
import kotlinx.coroutines.flow.first

/**
 * What a channel's options panel knows and does, the same from every surface. The guide's now and
 * next aren't used, as most surfaces that open a panel don't have guide rows to read them from.
 */
class ChannelOptions(private val graph: ServiceLocator.Graph) {
    val activeRecordings get() = graph.recordingRepository.observeActive()

    val sources get() = graph.sourceRepository.observeAll()

    fun stopRecording(recording: Recording) = graph.recordingEngine.stop(recording.id)

    /** [favourites] are the row ids the active Profile has as Favourites. */
    suspend fun factsAbout(channel: LineupChannel, favourites: Set<Long>): OptionsFor.Channel {
        val programmes = programmesOf(channel)
        return OptionsFor.Channel(
            favourite = channel.qualities.any { it.id in favourites },
            recording = recordingsOf(channel).isNotEmpty(),
            programmeOnNow = programmes.onNow(),
            hasUpcoming = programmes.later().isNotEmpty(),
        )
    }

    /** What Schedule a later programme lists: what's still to start on [channel], soonest first. */
    suspend fun laterProgrammes(channel: LineupChannel): List<Programme> =
        programmesOf(channel).later().take(MAX_LATER_PROGRAMMES)

    /** [channel] as the programme dialog takes it, whichever surface it was opened from. */
    suspend fun rowWithProgrammes(channel: LineupChannel) =
        ChannelsViewModel.Row(channel, programmes = programmesOf(channel))

    suspend fun openInExternalPlayer(context: Context, channel: LineupChannel) =
        openInExternalPlayer(context, graph.playbackSources, MediaKey.Live(channel.best.id))

    suspend fun choose(channel: LineupChannel, option: ItemOption) {
        val engine = graph.recordingEngine
        when (option) {
            ItemOption.AddFavourite -> graph.channelLineup.setFavourite(channel, true)
            ItemOption.RemoveFavourite -> graph.channelLineup.setFavourite(channel, false)
            ItemOption.HideChannel -> channel.qualities.forEach {
                graph.catalogRepository.setChannelHidden(it.id, true)
            }
            ItemOption.RecordNow -> engine.startChannel(channel.best, programmesOf(channel).onNow())
            // Every one: a channel's Qualities can each be recording at once.
            ItemOption.StopRecording -> recordingsOf(channel).forEach { engine.stop(it.id) }
            is ItemOption.RecordWholeSeries ->
                engine.recordSeries(channel.best, option.programme, programmesOf(channel))
            else -> Unit
        }
    }

    private suspend fun programmesOf(channel: LineupChannel): List<Programme> {
        val guideId = graph.channelLineup.guideId(channel) ?: return emptyList()
        return graph.epgRepository.upcoming(guideId, System.currentTimeMillis(), PROGRAMMES_LOOKED_UP)
    }

    private fun List<Programme>.onNow() = System.currentTimeMillis().let { now -> firstOrNull { it.isLiveAt(now) } }

    private fun List<Programme>.later() = System.currentTimeMillis().let { now -> filter { it.startUtcMillis > now } }

    private suspend fun recordingsOf(channel: LineupChannel) =
        activeRecordings.first().filter { active -> channel.qualities.any { it.id == active.channelId } }

    private companion object {
        /** As the series-rule rescan reads, so a series booked here finds the same airings. */
        const val PROGRAMMES_LOOKED_UP = 200
        const val MAX_LATER_PROGRAMMES = 8
    }
}
