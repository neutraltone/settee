/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv.data.repo

import app.opentv.core.AppDispatchers
import app.opentv.data.model.StreamLink
import app.opentv.data.model.StremioAddon
import app.opentv.data.model.StremioStream
import app.opentv.data.parser.ChannelNameNormalizer
import app.opentv.data.remote.AddonFailure
import app.opentv.data.remote.StremioClient
import app.opentv.player.DecoderList
import app.opentv.player.playsQualityRanked
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** A stream an add-on offered, read for the picker: its Quality and size, and whether it can play here. */
data class RankedStream(
    val stream: StremioStream,
    /** Higher is bigger, as channel Qualities rank; 0 when nothing the add-on said names one. */
    val qualityRank: Int,
    /** `4K`, `FHD`, `HD`, `SD`, or empty when nothing the add-on said names one. */
    val qualityLabel: String,
    /** Its size in bytes, from the add-on's hint or else its text, or null when neither says. */
    val sizeBytes: Long?,
    /** False when its Quality is bigger than this device's decoders take. */
    val playable: Boolean,
    /** How many people are sharing a torrent, as its add-on wrote it, or null when it didn't say. */
    val sharers: Int? = null,
)

/** How asking one add-on went. */
sealed interface AddonOutcome {
    val addonName: String

    data class Answered(override val addonName: String, val count: Int) : AddonOutcome

    data class AnsweredEmpty(override val addonName: String) : AddonOutcome

    /** It couldn't be asked: [failure] says why, for `ErrorWording.ofAddon`. */
    data class Unreachable(override val addonName: String, val failure: AddonFailure) : AddonOutcome
}

/** What a title's add-ons have offered so far. */
data class AddonStreamResults(
    /** Ranked best first, each link once. */
    val streams: List<RankedStream>,
    /** One per add-on that has answered or failed, in the viewer's add-on order. */
    val outcomes: List<AddonOutcome>,
    /** How many add-ons haven't answered yet. */
    val waitingOn: Int,
) {
    val finished: Boolean get() = waitingOn == 0
}

/**
 * The one thing that asks add-ons for streams. Each add-on is asked on its own, so a slow or broken one
 * never holds back the others, and what's found is ranked, labelled and de-duplicated as it arrives.
 * [addons] are the viewer's add-ons in their order (`AddonRepository.addons`).
 */
class AddonStreams(
    private val client: StremioClient,
    private val decoders: DecoderList,
    private val dispatchers: AppDispatchers,
    private val addons: suspend () -> List<StremioAddon>,
) {
    /** The streams for the film IMDb knows as [imdbId], growing as each add-on answers. */
    fun forFilm(imdbId: String): Flow<AddonStreamResults> = streamsFor(MOVIE, imdbId)

    private fun streamsFor(type: String, id: String): Flow<AddonStreamResults> = channelFlow {
        val asked = addons().filter { it.serves(type) }
        val answers = arrayOfNulls<Answer>(asked.size)
        // Answers land from several threads; one at a time keeps every result sent a whole one.
        val landing = Mutex()
        send(resultsOf(answers))
        asked.forEachIndexed { index, addon ->
            launch {
                val answer = withContext(dispatchers.io) { ask(addon, type, id) }
                landing.withLock {
                    answers[index] = answer
                    send(resultsOf(answers))
                }
            }
        }
    }

    // Only an AddonFailure: anything else, cancellation above all, isn't the add-on's to word.
    private fun ask(addon: StremioAddon, type: String, id: String): Answer = try {
        Answer(addon.name, client.streams(addon.manifestUrl, addon.name, type, id).map { it.ranked() }, null)
    } catch (failure: AddonFailure) {
        Answer(addon.name, emptyList(), failure)
    }

    private fun resultsOf(answers: Array<Answer?>): AddonStreamResults {
        val answered = answers.filterNotNull()
        // sortedWith is stable, so ties keep the add-on order and each add-on's own.
        val ranked = answered.flatMap { it.streams }.sortedWith(BEST_FIRST).distinctBy { it.stream.link.identity() }
        return AddonStreamResults(ranked, answered.map { it.outcome() }, answers.count { it == null })
    }

    private fun StremioStream.ranked(): RankedStream {
        val words = WORD.findAll(listOfNotNull(name, title, filename).joinToString(" ")).map { it.value }
        val rank = words.mapNotNull { ChannelNameNormalizer.qualityRankOfToken(it) }.maxOrNull() ?: 0
        return RankedStream(
            stream = this,
            qualityRank = rank,
            qualityLabel = QUALITY_LABELS[rank].orEmpty(),
            sizeBytes = videoSize ?: sizeIn(title) ?: name?.let(::sizeIn),
            playable = decoders.playsQualityRanked(rank),
            sharers = if (link is StreamLink.Torrent) sharersIn("$title ${name.orEmpty()}") else null,
        )
    }

    private class Answer(val addonName: String, val streams: List<RankedStream>, val failure: AddonFailure?) {
        fun outcome(): AddonOutcome = when {
            failure != null -> AddonOutcome.Unreachable(addonName, failure)
            streams.isEmpty() -> AddonOutcome.AnsweredEmpty(addonName)
            else -> AddonOutcome.Answered(addonName, streams.size)
        }
    }

    private companion object {
        const val MOVIE = "movie"
        const val STREAM = "stream"

        val WORD = Regex("[\\p{L}\\p{N}]+")
        val SIZE = Regex("(\\d+(?:[.,]\\d+)?)\\s*(TB|GB|MB|TiB|GiB|MiB)\\b", RegexOption.IGNORE_CASE)

        // Add-ons have no field for it, so they write it in their text: `👤 45`, or `Seeders: 45`.
        val SHARERS = Regex("(?:\uD83D\uDC64|\\bseed(?:er)?s?\\b)\\s*:?\\s*(\\d+)", RegexOption.IGNORE_CASE)
        val UNITS = mapOf("tb" to (1L shl 40), "gb" to (1L shl 30), "mb" to (1L shl 20))

        // A Quality too big for this device last, whatever it is; then links before torrents, which
        // need far more before they play; then the biggest Quality that plays; then, among torrents,
        // the one more people share, which starts sooner.
        val BEST_FIRST = compareByDescending<RankedStream> { it.playable }
            .thenBy { it.stream.link is StreamLink.Torrent }
            .thenByDescending { it.qualityRank }
            .thenByDescending { it.sharers ?: 0 }

        /**
         * An add-on that says what it serves is asked only for that. One stored before Settee read
         * that, and still not read again, is asked anyway: it's the add-on that knows.
         */
        fun StremioAddon.serves(type: String): Boolean =
            resources?.let { STREAM in it } != false && types?.takeIf { it.isNotEmpty() }?.let { type in it } != false

        /** A size written in [text], such as `2.1 GB`, in bytes. Add-ons write them binary, as Stremio does. */
        fun sizeIn(text: String): Long? = SIZE.find(text)?.let { match ->
            val number = match.groupValues[1].replace(',', '.').toDoubleOrNull()
            val unit = UNITS[match.groupValues[2].lowercase().replace("i", "")]
            if (number != null && unit != null) (number * unit).toLong() else null
        }

        /** How many people [text] says are sharing a torrent. */
        fun sharersIn(text: String): Int? = SHARERS.find(text)?.groupValues?.get(1)?.toIntOrNull()

        /** What makes two streams the same one, whichever add-on sent it. */
        fun StreamLink.identity(): String = when (this) {
            is StreamLink.Direct -> url
            is StreamLink.Torrent -> "$infoHash:$fileIdx"
        }
    }
}
