/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv.data.repo

import app.opentv.data.parser.ChannelNameNormalizer

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

internal val QUALITY_LABELS = mapOf(500 to "8K", 400 to "4K", 300 to "FHD", 200 to "HD", 100 to "SD")
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
