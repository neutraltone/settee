/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv.data.repo

import app.opentv.data.db.OpenTvDatabase
import app.opentv.data.model.Episode
import app.opentv.data.model.Series
import app.opentv.data.parser.ChannelNameNormalizer
import app.opentv.player.DecoderList
import app.opentv.player.playsQualityRanked
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Which Quality of a Movie or Episode plays. A provider lists each of a title's Qualities as a row of its
 * own in its own category (`4K-TOP - Superbad`, `EN - Superbad`), and the prefixes differ, so names don't
 * join them and browsing one category shows only one. What joins them is their TMDB id.
 */
class VodQualities(
    private val db: OpenTvDatabase,
    private val catalog: CatalogRepository,
    private val decoders: DecoderList,
) {
    /** One Quality: what plays it and what a viewer calls it, which may be nothing. */
    data class Quality(val key: MediaKey, val label: String)

    /**
     * [start] is what plays, and [lower] what to step down to, best first. [skipped] is what the title
     * opened was called, when this device isn't going to try it at all.
     */
    data class Plan(val start: Quality, val lower: List<Quality> = emptyList(), val skipped: String? = null)

    /** The Plan for what was opened. Anything without Qualities of this kind just plays. */
    suspend fun planFor(opened: MediaKey): Plan = when (opened) {
        is MediaKey.Movie -> movie(opened)
        is MediaKey.Episode -> episode(opened)
        else -> Plan(Quality(opened, ""))
    } ?: Plan(Quality(opened, ""))

    private suspend fun movie(opened: MediaKey.Movie): Plan? {
        val movie = db.movies().byId(opened.movieId) ?: return null
        val copies = known(movie.tmdbId)?.let { db.movies().byTmdbId(it) }.orEmpty().filter { it.id != movie.id }
        return plan(ranked(opened, movie.name), copies.map { ranked(MediaKey.Movie(it.id), it.name) })
    }

    private suspend fun episode(opened: MediaKey.Episode): Plan? {
        val episode = db.episodes().byId(opened.episodeId)
        val series = episode?.let { db.series().bySeriesId(it.sourceId, it.seriesId) }
        return series?.let { plan(ranked(opened, it.name), copiesOf(episode, it)) }
    }

    private suspend fun copiesOf(episode: Episode, series: Series): List<Ranked> {
        // A series' episodes are fetched when its page is opened, so another Quality's may never have
        // been. Waiting on the provider is only worth it when this device won't try the one opened.
        val fetch = !decoders.playsQualityRanked(rankOf(series.name))
        val copies = known(series.tmdbId)?.let { db.series().byTmdbId(it) }.orEmpty().filter { it.id != series.id }
        return copies.filter { decoders.playsQualityRanked(rankOf(it.name)) }.mapNotNull { copy ->
            sameEpisodeIn(copy, episode, fetch)?.let { ranked(MediaKey.Episode(it), copy.name) }
        }
    }

    private suspend fun sameEpisodeIn(copy: Series, episode: Episode, fetch: Boolean): Long? {
        suspend fun stored() = db.episodes().forSeries(copy.sourceId, copy.seriesId)
            .firstOrNull { it.season == episode.season && it.episodeNumber == episode.episodeNumber }?.id

        val source = if (fetch && stored() == null) db.sources().byId(copy.sourceId) else null
        source?.let { withTimeoutOrNull(FETCH_LIMIT_MILLIS) { catalog.ensureEpisodes(it, copy.seriesId) } }
        return stored()
    }

    private fun plan(self: Ranked, copies: List<Ranked>): Plan {
        val selfPlays = decoders.playsQualityRanked(self.rank)
        // A row with no tag says nothing of its size, so a tagged copy isn't known to be any bigger.
        val below = copies.filter {
            decoders.playsQualityRanked(it.rank) && (!selfPlays || self.rank == 0 || it.rank <= self.rank)
        }
            // The same shelf first: a provider's shelves differ in language as well as quality.
            .sortedWith(
                compareByDescending<Ranked> { it.shelf != null && it.shelf == self.shelf }.thenByDescending { it.rank },
            )
            .map { it.quality }
        return if (selfPlays || below.isEmpty()) {
            Plan(self.quality, below)
        } else {
            Plan(below.first(), below.drop(1), self.quality.label)
        }
    }

    private class Ranked(val quality: Quality, val rank: Int, val shelf: String?)

    private fun ranked(key: MediaKey, name: String): Ranked {
        val normalized = ChannelNameNormalizer.normalize(name)
        val shelf = SHELF.find(name)?.groupValues?.get(1)?.lowercase()
        return Ranked(Quality(key, normalized.qualityLabel), normalized.qualityRank, shelf)
    }

    private fun rankOf(name: String) = ChannelNameNormalizer.normalize(name).qualityRank

    /** Panels send 0 or nothing for a title they don't know, which would make every such title one. */
    private fun known(tmdbId: String?): String? = tmdbId?.takeIf { it.isNotBlank() && it != "0" }

    private companion object {
        const val FETCH_LIMIT_MILLIS = 10_000L

        /** What's before the title in `4K-EN - Superbad` and `EN - Superbad`: the same shelf, EN. */
        val SHELF = Regex("""^\s*(?:[48]K-)?(.+?)\s+-\s""", RegexOption.IGNORE_CASE)
    }
}
