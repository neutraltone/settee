/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv.ui

import app.opentv.core.ViewerTime
import app.opentv.data.model.GuideProgramme
import app.opentv.data.repo.GUIDE_SETTLE_MILLIS
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.withIndex
import kotlinx.coroutines.launch

/**
 * A guide window's programmes grouped by EPG channel id, with the window they're for: null only for
 * [Empty], the placeholder before the first listings are read.
 *
 * Compared by identity, not content: the listings are already never sent twice in a row (see
 * [app.opentv.data.repo.settledReads]), and comparing a whole window's programmes again here cost a
 * second full pass over them on every update.
 */
internal class ProgrammeIndex(val window: ViewerTime.Window?, val byChannel: Map<String, List<GuideProgramme>>) {
    companion object {
        val Empty = ProgrammeIndex(null, emptyMap())
    }
}

/** Each guide window's stored listings, grouped by channel; a new window starts reading afresh. */
@OptIn(ExperimentalCoroutinesApi::class)
internal fun Flow<ViewerTime.Window>.guideIndex(
    programmesIn: (fromUtcMillis: Long, toUtcMillis: Long) -> Flow<List<GuideProgramme>>,
): Flow<ProgrammeIndex> = flatMapLatest { window ->
    programmesIn(window.startUtcMillis, window.endUtcMillis)
        .map { programmes -> ProgrammeIndex(window, programmes.groupBy { it.epgChannelId }) }
}

/**
 * Builds from the latest channels and [index]. New channels, or the first listings for a new window
 * (opening the guide, the next half hour, another day), build as soon as the build before has
 * finished; more listings for the same window wait until [settleMillis] after it finished.
 *
 * The pause runs from the end of a build, not from when the listings arrived, because building
 * "All channels" for a big provider takes far longer than the pause on a Fire TV Stick: paced from
 * arrival, a newer index was always waiting when a build ended, and the guide rebuilt back to back
 * for the whole of a guide sync.
 *
 * [remembered] holds what the last build was made from, across collections: coming back to the
 * guide, the index replays the listings it last held, and those rows are already on screen.
 */
internal fun <C, R> Flow<C>.guideBuilds(
    index: Flow<ProgrammeIndex>,
    remembered: GuideBuildMemory<C>,
    settleMillis: Long = GUIDE_SETTLE_MILLIS,
    build: suspend (channels: C, byChannel: Map<String, List<GuideProgramme>>) -> R,
): Flow<R> = channelFlow {
    val latest = MutableStateFlow<BuildInputs<C>?>(null)
    launch {
        combine(this@guideBuilds.withIndex(), index) { channels, programmes ->
            BuildInputs(channels.value, channels.index, programmes)
        }.collect { latest.value = it }
    }
    val settledSinceBuild = MutableStateFlow(true)
    var settling: Job? = null
    var built: BuildInputs<C>? = null
    while (true) {
        val inputs = combine(latest, settledSinceBuild) { next, settled -> next?.takeIf { it.due(built, settled) } }
            .filterNotNull()
            .first()
        val onScreen = built == null && remembered.built(inputs.channels, inputs.index)
        built = inputs
        if (onScreen) continue
        send(build(inputs.channels, inputs.index.byChannel))
        remembered.keep(inputs.channels, inputs.index)
        settledSinceBuild.value = false
        settling?.cancel()
        settling = launch {
            delay(settleMillis)
            settledSinceBuild.value = true
        }
    }
}

/**
 * What the guide's rows were last built from, kept by their view model rather than inside
 * [guideBuilds], whose locals start again on every collection (#374). The rows stop five seconds
 * after Live TV is covered; on coming back, the index replays the listings it last held, and
 * without this the first build repeated the rows already on screen, holding up the fresh listings
 * for a whole extra build and the pause after it.
 *
 * Channels are compared by content: each collection reads them again as a new list. The index is
 * compared by identity, as everywhere else in the guide.
 */
internal class GuideBuildMemory<C> {
    @Volatile private var last: Pair<C, ProgrammeIndex>? = null

    fun built(channels: C, index: ProgrammeIndex): Boolean =
        last?.let { (lastChannels, lastIndex) -> lastIndex === index && lastChannels == channels } ?: false

    fun keep(channels: C, index: ProgrammeIndex) {
        last = channels to index
    }
}

/** One build's inputs, compared by identity: each is a new arrival from one input or the other. */
private class BuildInputs<C>(val channels: C, val channelsVersion: Int, val index: ProgrammeIndex) {
    fun due(built: BuildInputs<C>?, settled: Boolean): Boolean = when {
        built == null -> true
        this === built -> false
        channelsVersion != built.channelsVersion || index.window != built.index.window -> true
        else -> settled
    }
}
