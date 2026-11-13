/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv.data.repo

import app.opentv.data.db.OpenTvDatabase
import app.opentv.data.model.Movie
import app.opentv.data.model.Series
import app.opentv.data.parser.ChannelNameNormalizer
import app.opentv.data.parser.VodTitleCleaner
import app.opentv.player.DecoderList
import app.opentv.player.playsQualityRanked

/**
 * A provider's rows of films or shows as titles, each title its Versions in the order given, titles in
 * the order of their first row. Rows join by TMDB id; names miss a missing year, chart numbering and
 * translated titles, and join different films that share a name (ADR 0007). Only a row with no TMDB id
 * falls back to its cleaned name and year: it joins the title with an id of that name and year when
 * there's exactly one, since two would be a guess, and otherwise the other rows without one.
 */
internal fun <T> groupVersions(
    items: List<T>,
    tmdbIdOf: (T) -> String?,
    nameOf: (T) -> String,
    yearOf: (T) -> Int?,
): List<List<T>> {
    val names = items.map { nameKey(nameOf(it), yearOf(it)) }
    val ids = items.map { knownTmdbId(tmdbIdOf(it)) }
    val idsByName = HashMap<String, MutableSet<String>>()
    names.zip(ids).forEach { (name, id) -> if (id != null) idsByName.getOrPut(name) { HashSet() } += id }
    val titles = LinkedHashMap<String, MutableList<T>>()
    items.forEachIndexed { i, item ->
        val id = ids[i] ?: idsByName[names[i]]?.singleOrNull()
        val key = id?.let { "tmdb:$it" } ?: "name:${names[i]}"
        titles.getOrPut(key) { ArrayList(1) } += item
    }
    return titles.values.toList()
}

/**
 * A title's one badge, from its [versions]: the best known Quality among those [decoders] can play (4K,
 * FHD or HD), CAM when only cam copies play, and none when the best is untagged. When nothing plays here
 * it's the 4K that can't, so a viewer sees why before opening it (docs/LOOK.md).
 */
fun titleBadge(versions: List<VersionTraits>, decoders: DecoderList): String? {
    val playable = versions.filter { decoders.playsQualityRanked(it.qualityRank) }
    val shown = playable.filterNot { it.cam }
    return when {
        playable.isEmpty() -> versions.maxByOrNull { it.qualityRank }?.qualityLabel?.takeIf { it.isNotEmpty() }
        shown.isEmpty() -> CAM_BADGE
        else -> shown.maxOf { it.qualityRank }.let(QUALITY_LABELS::get)?.takeIf { it in BADGE_QUALITIES }
    }
}

/** The badge a [name] would get on its own, knowing nothing of the device or other Versions. */
internal fun nameBadge(name: String): String? = versionTraits(name, null).qualityLabel.takeIf { it in BADGE_QUALITIES }

private const val CAM_BADGE = "CAM"
private val BADGE_QUALITIES = setOf("8K", "4K", "FHD", "HD")

/** Panels send 0 or nothing for a title they don't know, which would make every such title one. */
internal fun knownTmdbId(tmdbId: String?): String? = tmdbId?.trim()?.takeIf { it.isNotEmpty() && it != "0" }

/** A standalone 4-digit release year (19xx/20xx) as it appears inside a VOD title. */
private val TITLE_YEAR = Regex("""\b(19|20)\d{2}\b""")

/** The name without its shelf, Quality and year, plus the year: the field's, else the one in the name. */
private fun nameKey(name: String, year: Int?): String {
    val embedded = TITLE_YEAR.find(name)?.value?.toIntOrNull()
    // The cleaner takes off the shelf (`4K-EN -`, `NF -`), which the normalizer would keep as words.
    val normalized = ChannelNameNormalizer.normalize(VodTitleCleaner.clean(TITLE_YEAR.replace(name, " ")))
    return normalized.groupKey + "|" + ((year ?: embedded)?.toString() ?: "")
}

/** Every Version of [movie] by its TMDB id, itself included: just itself when it has none. */
internal suspend fun OpenTvDatabase.versionsOf(movie: Movie): List<Movie> =
    knownTmdbId(movie.tmdbId)?.let { movies().byTmdbId(it) }.orEmpty().ifEmpty { listOf(movie) }

/** Every Version of [series] by its TMDB id, itself included: just itself when it has none. */
internal suspend fun OpenTvDatabase.versionsOf(series: Series): List<Series> =
    knownTmdbId(series.tmdbId)?.let { series().byTmdbId(it) }.orEmpty().ifEmpty { listOf(series) }
