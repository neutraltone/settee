/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv.data.parser

import java.text.Normalizer

/**
 * Turns the channel names IPTV providers actually ship into something usable.
 *
 * A real provider list contains entries like:
 *
 *     UK| BBC ONE FHD
 *     UK - BBC One HD
 *     [UK] BBC 1 ᵁᴴᴰ
 *     ### PRIME RAW 60fps ####
 *
 * while an XMLTV guide calls the same channel `BBC One`. Nothing in either format joins
 * them. This normaliser produces, for any name:
 *
 *  - a **base name** with country prefixes, quality tags and decorations stripped
 *  - a **group key** (lowercased alphanumerics of the base) used to fold quality variants
 *    of one channel into a single row, and to match against guide data
 *  - a **quality rank and label**, so the app can pick the best variant by default and
 *    offer the rest as a switch during playback
 *
 * This one object is the foundation of both headline features: EPG matching and quality
 * grouping. It is pure and heavily tested — when a channel matches the wrong guide entry,
 * the fix almost certainly starts here, in a test case named after the offending channel.
 */
object ChannelNameNormalizer {

    data class Normalized(
        /** Human-readable name with the junk removed: `BBC One`. */
        val baseName: String,
        /**
         * The key guide matching and category locks join on: lowercase letters, marks and digits, in
         * any script (`bbc1`). When no name is left it falls back to the whole raw name's key, which
         * is fine for a guide's or a category's own name. Anything that groups *rows* by name, as
         * channels and titles do, uses [nameKey] or [channelKey] instead.
         */
        val groupKey: String,
        /** Higher is better. 0 when the name carries no quality information. */
        val qualityRank: Int,
        /** What to show on the quality switch: `FHD`, `HD`, `RAW 60fps`, or `""`. */
        val qualityLabel: String,
        /** Country hint from a stripped prefix (`UK`, `US`…), or null. */
        val region: String?,
        /**
         * The key of the name alone, once prefix and tags are gone: [groupKey] with no fallback, so
         * empty when nothing is left (`RU| HD`). What rows group on, as an empty one groups nothing.
         */
        val nameKey: String,
    ) {
        /**
         * What a channel row is stored under: [groupKey] with the region in front (`uk:itv`), so
         * `UK| ITV` and `US| ITV` stay two channels. Guide matching and category locks use the bare
         * [groupKey]: a guide calls both of them `ITV`. Empty when there's no [nameKey]: the raw-name
         * fallback is only the region and tags, and would fold unrelated channels into one (#345).
         */
        val channelKey: String
            get() = when {
                nameKey.isEmpty() -> ""
                region == null -> nameKey
                else -> "${region.lowercase()}:$nameKey"
            }
    }

    /** Quality/codec tokens, matched as whole words, case-insensitively. */
    private const val RANK_8K = 500
    private const val RANK_4K = 400
    private const val HEIGHT_8K = 4320
    private const val HEIGHT_4K = 2160

    private val QUALITY_RANKS = mapOf(
        "8K" to RANK_8K,
        "4K" to RANK_4K, "UHD" to RANK_4K, "FUHD" to RANK_4K, "2160P" to RANK_4K,
        "FHD" to 300, "1080" to 300, "1080P" to 300, "FULLHD" to 300,
        "HD" to 200, "720P" to 200, "720" to 200,
        "SD" to 100, "LQ" to 100, "480P" to 100, "576P" to 100,
    )

    /** Tokens that describe the stream but not its resolution. Stripped, kept in the label. */
    private val EXTRA_TOKENS = setOf(
        "RAW", "HEVC", "H265", "H.265", "H264", "H.264", "AV1",
        "50FPS", "60FPS", "25FPS", "30FPS", "FPS",
        "HDR", "DOLBY", "VISION", "PLUS",
    )

    /**
     * Unicode modifier/superscript letters folded to ASCII before tokenising.
     *
     * Providers love these as anti-copy decoration: `ᴴᴰ`, `ʰᵉᵛᶜ`, `ᴿᴬᵂ`, `ᵁᴴᴰ`, `⁸ᴷ`,
     * `³⁸⁴⁰ᴾ`. Both cases exist in the wild — often in the SAME list — so this covers the
     * full A–Z in both the uppercase modifier block and the lowercase superscript block,
     * plus superscript digits. Without the lowercase half, `ʰᵉᵛᶜ`/`ᴿᴬᵂ` survive into the
     * channel name and read as junk, which is the single biggest source of a "messy" list.
     */
    private val SUPERSCRIPT_MAP: Map<Char, Char> = buildMap {
        // Uppercase modifier letters (U+1D2C block, plus strays).
        val upper = "ᴬ:A,ᴮ:B,ꟲ:C,ᴰ:D,ᴱ:E,ꟳ:F,ᴳ:G,ᴴ:H,ᴵ:I,ᴶ:J,ᴷ:K,ᴸ:L,ᴹ:M,ᴺ:N," +
            "ᴼ:O,ᴾ:P,ꟴ:Q,ᴿ:R,ᵀ:T,ᵁ:U,ⱽ:V,ᵂ:W,ᔆ:S"
        // Lowercase superscript/modifier letters.
        val lower = "ᵃ:a,ᵇ:b,ᶜ:c,ᵈ:d,ᵉ:e,ᶠ:f,ᵍ:g,ʰ:h,ᶦ:i,ʲ:j,ᵏ:k,ˡ:l,ᵐ:m,ⁿ:n," +
            "ᵒ:o,ᵖ:p,ʳ:r,ˢ:s,ᵗ:t,ᵘ:u,ᵛ:v,ʷ:w,ˣ:x,ʸ:y,ᶻ:z"
        val digits = "⁰:0,¹:1,²:2,³:3,⁴:4,⁵:5,⁶:6,⁷:7,⁸:8,⁹:9"
        // Small capitals (`ꜱᴘᴏʀᴛꜱ`), the same decoration. Dropped with everything non-ASCII until
        // #345; now that keys keep every script, unfolded they'd keep a name from its guide.
        val smallCaps = "ᴀ:A,ʙ:B,ᴄ:C,ᴅ:D,ᴇ:E,ꜰ:F,ɢ:G,ʜ:H,ɪ:I,ᴊ:J,ᴋ:K,ʟ:L,ᴍ:M,ɴ:N," +
            "ᴏ:O,ᴘ:P,ꞯ:Q,ʀ:R,ꜱ:S,ᴛ:T,ᴜ:U,ᴠ:V,ᴡ:W,ʏ:Y,ᴢ:Z"
        for (chunk in "$upper,$lower,$digits,$smallCaps".split(',')) {
            if (chunk.length >= 3) put(chunk[0], chunk[2])
        }
    }

    /**
     * Leading country tag: `UK|`, `UK -`, `UK:`, `[UK]`, `(US)`, `USA |` and so on.
     * Two or three capitals only — long enough for ISO codes and `USA`, short enough that
     * `BEIN| SPORTS` is left alone.
     *
     * Two alternatives on purpose. Brackets are themselves the delimiter, so `[UK] ITV`
     * needs no separator after them; a bare code **must** have one, or `BBC ONE` would
     * lose its BBC.
     */
    private val COUNTRY_PREFIX = Regex(
        """^\s*(?:[\[(]([A-Z]{2,3})[\])]\s*[|:\-–—]?|([A-Z]{2,3})\s*[|:\-–—])\s*""",
    )

    /**
     * Decorations providers use as separators or eye-catchers — stripped from the display
     * name. Broad on purpose: anything outside letters, digits, spaces and the handful of
     * punctuation marks that appear inside real names (& - + . / : | ( )) is decoration.
     * The groupKey ignores all of it anyway, so matching is unaffected either way; this is
     * purely so the name on screen reads like a channel and not a ransom note.
     */
    private val DECORATION = Regex("""[^\p{L}\p{M}\p{Nd} &+.\-/:|()']+""")

    private val MULTI_SPACE = Regex("""\s+""")

    /**
     * Folds unicode superscript/modifier decorations to ASCII (`ᴴᴰ` → `HD`, `ᴿᴬᵂ` → `RAW`).
     *
     * Exposed so other cleaners — notably [app.opentv.data.parser.VodTitleCleaner] for VOD titles —
     * reuse the exact same decoration table instead of maintaining a second, drifting copy. Returns
     * the input untouched (and without allocating) when it carries no such characters.
     */
    fun foldSuperscripts(raw: String): String {
        if (raw.none { it in SUPERSCRIPT_MAP }) return raw
        return buildString(raw.length) {
            for (c in raw) append(SUPERSCRIPT_MAP[c] ?: c)
        }
    }

    /**
     * The quality rank of a single token if it is a known resolution/quality tag (`4K`, `FHD`, `HD`,
     * `SD`, `UHD`…), matched whole-word and case-insensitively, else null. Exposed so the VOD title
     * cleaner strips exactly the tags this normaliser recognises rather than reinventing the list.
     */
    fun qualityRankOfToken(token: String): Int? = QUALITY_RANKS[token.uppercase()]

    /** The height a Quality ranked [qualityRank] says it has, when that's more than every device plays. */
    fun heightPromisedBy(qualityRank: Int): Int? = when {
        qualityRank >= RANK_8K -> HEIGHT_8K
        qualityRank >= RANK_4K -> HEIGHT_4K
        else -> null
    }

    /** Whether a single token is a non-resolution stream marker (`RAW`, `HEVC`, `60FPS`, `HDR`…). */
    fun isStreamMarker(token: String): Boolean = token.uppercase() in EXTRA_TOKENS

    fun normalize(raw: String): Normalized {
        // Fold superscript decorations (ᴴᴰ → HD) so the token pass can see them.
        var working = foldSuperscripts(canonical(raw.trim()))

        // Country prefix BEFORE decoration stripping: the brackets in `[UK]` are the
        // delimiter, and the decoration pass would eat them and lose the tag.
        var region: String? = null
        COUNTRY_PREFIX.find(working)?.let { match ->
            region = match.groupValues[1].ifEmpty { match.groupValues[2] }
            working = working.removeRange(match.range)
        }

        working = DECORATION.replace(working, " ")

        // Tokenise and pull out quality/extra markers wherever they appear.
        var bestRank = 0
        val labelParts = ArrayList<String>(2)
        val keptTokens = ArrayList<String>()

        for (token in working.split(' ', '\t', '/').filter { it.isNotBlank() }) {
            val bare = token.trim('.', ',', ':', ';', '|', '-', '(', ')', '[', ']')
            if (bare.isEmpty()) continue
            val upper = bare.uppercase()

            val rank = QUALITY_RANKS[upper]
            // `4K-TOP - Superbad`: a provider's 4K shelf, glued to its name. Only these tags, as `Hd-1`
            // or `Fuhd-1` are as likely to be a name.
            val glued = upper.substringBefore('-', "").takeIf { it == "4K" || it == "8K" }?.let(QUALITY_RANKS::get)
            when {
                glued != null -> {
                    if (glued > bestRank) bestRank = glued
                    labelParts += upper.substringBefore('-')
                    keptTokens += bare.substringAfter('-')
                }
                rank != null -> {
                    if (rank > bestRank) bestRank = rank
                    labelParts += upper
                }
                upper in EXTRA_TOKENS -> labelParts += bare
                else -> keptTokens += bare
            }
        }

        val base = MULTI_SPACE.replace(keptTokens.joinToString(" "), " ").trim()
        val key = groupKeyOf(base)

        return Normalized(
            baseName = base.ifEmpty { raw.trim() },
            groupKey = key.ifEmpty { groupKeyOf(raw) },
            qualityRank = bestRank,
            qualityLabel = labelParts.joinToString(" "),
            region = region,
            nameKey = key,
        )
    }

    /**
     * The canonical join key: lowercase letters and digits only, in any script.
     *
     * Aggressive on purpose. `BBC One`, `BBC1`, `bbc-one` and `B B C One` should all meet in
     * the middle — false negatives here mean channels with no guide, which is the failure
     * users actually notice. The word-number folding handles `BBC ONE` vs `BBC 1`.
     */
    fun groupKeyOf(name: String): String = keyOf(canonical(name), CAMEL_BOUNDARY, NON_ALNUM)

    /**
     * [Normalized.groupKey] as `NORMALIZER_VERSION` 3 and earlier made it, from ASCII letters and
     * digits only: what Parental lock saved its category keys under then, so they can be carried to
     * today's keys (#345). Nothing else should use it.
     */
    internal fun asciiOnlyGroupKey(raw: String): String =
        keyOf(normalize(raw).baseName, ASCII_CAMEL_BOUNDARY, ASCII_NON_ALNUM)
            .ifEmpty { keyOf(raw, ASCII_CAMEL_BOUNDARY, ASCII_NON_ALNUM) }

    /**
     * One spelling for text that reads the same: composed accents (NFC, so `é` typed as `e` and a
     * combining accent meets `é`) and full-width ASCII (`ＮＨＫ`, `ＨＤ`) as ordinary letters. Not NFKC,
     * which would also make `™` into `TM` and `½` into `1⁄2`, words in the key that weren't there.
     */
    private fun canonical(text: String): String {
        if (text.all { it < '\u0080' }) return text
        val composed = Normalizer.normalize(text, Normalizer.Form.NFC)
        return if (composed.none { it in FULL_WIDTH || it == IDEOGRAPHIC_SPACE }) {
            composed
        } else {
            buildString(composed.length) {
                for (c in composed) {
                    append(
                        when (c) {
                            in FULL_WIDTH -> c - FULL_WIDTH_OFFSET
                            IDEOGRAPHIC_SPACE -> ' '
                            else -> c
                        },
                    )
                }
            }
        }
    }

    private val FULL_WIDTH = '\uFF01'..'\uFF5E'
    private const val FULL_WIDTH_OFFSET = 0xFEE0
    private const val IDEOGRAPHIC_SPACE = '\u3000'
    private const val DOTTED_I = "i\u0307"

    private fun keyOf(name: String, camelBoundary: Regex, separators: Regex): String {
        // Split CamelCase first so `BbcOne` sees the same words as `BBC One`, then fold
        // number WORDS to digits — whole words only, never substrings, or `money` becomes
        // `m1y` on one side of a match and not the other.
        val spaced = camelBoundary.replace(name, "$1 $2")
        // Lowercasing Turkish `İ` gives `i` plus a combining dot, which the key now keeps as a mark:
        // without dropping it, `TRT İzle` would stop meeting a guide's `TRT Izle`.
        return spaced.lowercase().replace(DOTTED_I, "i")
            .split(separators)
            .filter { it.isNotEmpty() }
            .joinToString("") { word -> NUMBER_WORDS[word] ?: word }
    }

    /** The [Normalized.groupKey] inside a stored [Normalized.channelKey], region dropped. */
    fun nameKeyOf(channelKey: String): String = channelKey.substringAfter(':')

    private val CAMEL_BOUNDARY = Regex("""(\p{Ll})(\p{Lu})""")

    // Every script, not only ASCII: an ASCII-only key left `RU| НТВ` as just its region, so every
    // Cyrillic channel of a region folded into one (#345). Each script stays as written: no
    // transliteration, so `Россия 1` and `Rossiya 1` don't meet. Marks stay too: they're the
    // vowels of Devanagari, Thai and Bengali, and dropping them folds different names into one.
    private val NON_ALNUM = Regex("""[^\p{L}\p{M}\p{Nd}]+""")
    private val ASCII_CAMEL_BOUNDARY = Regex("""([a-z])([A-Z])""")
    private val ASCII_NON_ALNUM = Regex("""[^a-zA-Z0-9]+""")
    private val NUMBER_WORDS = mapOf(
        "one" to "1", "two" to "2", "three" to "3", "four" to "4", "five" to "5",
        "six" to "6", "seven" to "7", "eight" to "8", "nine" to "9",
    )
}
