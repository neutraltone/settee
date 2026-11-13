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
 * What a Version is, as its name and its category's name say: what the Quality pill tells apart. Two
 * copies on different shelves with nothing else between them are equal, and collapse to one entry.
 */
data class VersionTraits(
    /** Higher plays first; 0 when untagged, which says nothing of its size, not that it's HD. */
    val qualityRank: Int = 0,
    /** `4K`, `FHD`, `HD`, `SD`, or empty when untagged. */
    val qualityLabel: String = "",
    /** The ISO 639-1 language its shelf or category names, or null when neither names one. */
    val language: String? = null,
    /** Dubbed, whether or not it says into what. */
    val dubbed: Boolean = false,
    /** The ISO 639-1 language of its subtitles, `multi` for several, or null when it has none it names. */
    val subtitles: String? = null,
    /** `Dolby` or `Atmos` when its name or category promises it. */
    val sound: String? = null,
    /** Filmed in a cinema: never chosen on its own while anything else plays. */
    val cam: Boolean = false,
) {
    /**
     * Whether this Version is in [deviceLanguage], so Settee may switch to it unasked. Unmarked counts:
     * a shelf only names a language when it's an exception. A dub into who knows what, or subtitles in
     * one language, doesn't.
     */
    fun inLanguage(deviceLanguage: String): Boolean {
        val spoken = language?.let { it == deviceLanguage } ?: !dubbed
        val read = subtitles == null || subtitles == MULTI || subtitles == deviceLanguage
        return spoken && read
    }

    companion object {
        const val MULTI = "multi"
    }
}

/** A Version's [VersionTraits] from its provider [name] and its category's name, when known. */
fun versionTraits(name: String, category: String?): VersionTraits {
    val rank = ChannelNameNormalizer.normalize(name).qualityRank
    // Only the shelf before the title names a language: a code after it, `Love 101 (2020) (TR)`, is
    // where it comes from. In a category, a two-letter word is as likely to be anything, so only whole
    // language names count there, and the category's own shelf before ` - `.
    // A shelf is written in capitals, which keeps `It - Chapter Two` English.
    val shelfWords = (shelfOf(name) + shelfOf(category.orEmpty())).filter { it == it.uppercase() }
    val allWords = words(name) + words(category.orEmpty())
    var language: String? = null
    var subtitles: String? = null
    allWords.forEachIndexed { i, word ->
        val named = LANGUAGE_NAMES[word]
        val next = allWords.getOrNull(i + 1)
        when {
            word == "MULTI" && next in SUBTITLE_WORDS -> subtitles = VersionTraits.MULTI
            named != null && next in SUBTITLE_WORDS -> subtitles = subtitles ?: named
            named != null -> language = language ?: named
        }
    }
    language = language ?: shelfWords.firstNotNullOfOrNull { LANGUAGE_CODES[it] }
    return VersionTraits(
        qualityRank = rank,
        qualityLabel = QUALITY_LABELS[rank].orEmpty(),
        language = language,
        dubbed = allWords.any { it in DUB_WORDS },
        subtitles = subtitles,
        sound = when {
            "ATMOS" in allWords -> "Atmos"
            "DOLBY" in allWords && "VISION" !in allWords -> "Dolby"
            else -> null
        },
        cam = allWords.any { it in CAM_WORDS },
    )
}

private fun words(text: String): List<String> = text.uppercase().split(NOT_A_WORD).filter { it.isNotEmpty() }

private fun shelfOf(text: String): List<String> =
    SHELF.find(text)?.groupValues?.get(1).orEmpty().split(NOT_A_WORD).filter { it.isNotEmpty() }

/** What's before the title: `4K-EN` in `4K-EN - Superbad`. */
private val SHELF = Regex("""^\s*(.+?)\s+[-|]\s""")
private val NOT_A_WORD = Regex("""[^\p{L}\p{Nd}+]+""")

private val QUALITY_LABELS = mapOf(500 to "8K", 400 to "4K", 300 to "FHD", 200 to "HD", 100 to "SD")
private val SUBTITLE_WORDS = setOf("SUB", "SUBS", "SUBBED", "SUBTITLES", "SUBTITLED")
private val DUB_WORDS = setOf("DUB", "DUBS", "DUBBED")
private val CAM_WORDS = setOf("CAM", "HDCAM", "CAMRIP", "TS", "HDTS", "TELESYNC")

/** Languages by their names, as categories spell them out. */
private val LANGUAGE_NAMES = mapOf(
    "ENGLISH" to "en", "GERMAN" to "de", "DEUTSCH" to "de", "ITALIAN" to "it", "ITALIANO" to "it",
    "FRENCH" to "fr", "FRANCAIS" to "fr", "SPANISH" to "es", "ESPANOL" to "es", "LATINO" to "es",
    "ARABIC" to "ar", "TURKISH" to "tr", "PORTUGUESE" to "pt", "DUTCH" to "nl", "POLISH" to "pl",
    "RUSSIAN" to "ru", "HINDI" to "hi", "GREEK" to "el", "SWEDISH" to "sv", "DANISH" to "da",
    "NORWEGIAN" to "no", "FINNISH" to "fi", "ROMANIAN" to "ro", "HUNGARIAN" to "hu", "CZECH" to "cs",
    "ALBANIAN" to "sq", "PERSIAN" to "fa", "KOREAN" to "ko", "JAPANESE" to "ja", "CHINESE" to "zh",
)

/** Languages by the codes a shelf uses. */
private val LANGUAGE_CODES = mapOf(
    "EN" to "en", "ENG" to "en", "DE" to "de", "GER" to "de", "IT" to "it", "ITA" to "it",
    "FR" to "fr", "FRA" to "fr", "ES" to "es", "ESP" to "es", "LAT" to "es", "AR" to "ar", "ARA" to "ar",
    "TR" to "tr", "TUR" to "tr", "PT" to "pt", "BR" to "pt", "NL" to "nl", "PL" to "pl", "RU" to "ru",
    "HI" to "hi", "GR" to "el", "SE" to "sv", "SWE" to "sv", "DK" to "da", "NO" to "no", "FI" to "fi",
    "RO" to "ro", "HU" to "hu", "CZ" to "cs", "ALB" to "sq", "IR" to "fa", "KR" to "ko", "JP" to "ja",
)

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
