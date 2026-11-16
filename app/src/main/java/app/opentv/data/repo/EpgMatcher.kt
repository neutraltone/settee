/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv.data.repo

import app.opentv.data.model.GuideAlias
import app.opentv.data.parser.ChannelNameNormalizer

/**
 * Joins provider channels to guide channels when nobody gave us a join key.
 *
 * The provider says `UK| BBC ONE FHD`. The guide says `BBC One` (id `bbc1.uk`). The only
 * bridge between them is the name, so both sides are pushed through
 * [ChannelNameNormalizer.groupKeyOf] and matched on the result.
 *
 * ## The design rule: prefer no match to a wrong match
 *
 * A missing guide entry reads as "provider doesn't do EPG" and can be fixed with a manual
 * override. A *wrong* one shows EastEnders against a sports channel, and the user reasonably
 * concludes the whole guide is broken. So the fuzzy tier only accepts a prefix match when it
 * is **unambiguous** — exactly one guide channel fits. `bbcone` will claim `BBC One` when
 * that is the only candidate, but if the guide carries `BBC One London` *and* `BBC One
 * Wales` and nothing plain, we refuse to guess between regions.
 *
 * That refusal is within one guide. Two guides naming the same channel each with their own id
 * (Freeview's `bbc1.freeview`, epgshare's `BBCOne.HD.uk`) aren't a guess between channels, so
 * one guide's id wins instead; see [buildIndex] for which. An exact name still beats a prefix,
 * whichever guide it's in.
 */
object EpgMatcher {

    /** Every guide's lookup, the one that wins a key first (see [buildIndex]). */
    class Index internal constructor(private val guides: List<GuideIndex>) {
        /** Returns the epg id for a provider channel's group key, or null. */
        fun match(groupKey: String): String? {
            if (groupKey.length < MIN_KEY_LENGTH) return null
            // An exact name in any guide beats a prefix in a higher one: a prefix is a guess, and an
            // exact name, even under another guide's id, is the same channel. A guide that lists the
            // key under two channels refuses it, rather than falling through to a lower guide.
            for (guide in guides) if (guide.has(groupKey)) return guide.exact(groupKey)
            // Prefix tier: the first guide with any candidate decides; another guide spelling the
            // channel differently is no reason to refuse, but a guide disagreeing with itself is.
            for (guide in guides) {
                when (val verdict = guide.prefix(groupKey)) {
                    Prefix.None -> continue
                    Prefix.Refused -> return null
                    is Prefix.Found -> return verdict.epgId
                }
            }
            return null
        }
    }

    internal sealed interface Prefix {
        data object None : Prefix
        data object Refused : Prefix
        data class Found(val epgId: String) : Prefix
    }

    /**
     * One guide's lookup.
     *
     * @param guideIdByKey Every guide key and the one guide channel it names, or null when two
     *   channels share it.
     */
    internal class GuideIndex(private val guideIdByKey: Map<String, String?>) {
        /** In order, because keys extending a name sit next to each other. */
        private val sortedKeys = guideIdByKey.keys.sorted().toTypedArray()

        fun has(key: String) = key in guideIdByKey

        fun exact(key: String) = guideIdByKey[key]

        /**
         * The provider name extends the guide name or vice versa ("skysportsmainevent" vs
         * "skysportsmainevt"; "bbcone" vs "bbconelondon"). Only accepted when every candidate agrees
         * on a single epg id.
         *
         * Not by checking each guide key in turn: that's channels x aliases, and it took most of
         * the matcher's time on a Fire TV Stick (#177).
         */
        fun prefix(groupKey: String): Prefix {
            var found: String? = null
            for (id in candidates(groupKey)) {
                if (id == null || (found != null && found != id)) return Prefix.Refused
                found = id
            }
            return found?.let { Prefix.Found(it) } ?: Prefix.None
        }

        /**
         * Each guide key the provider key extends, then each that extends it, as what they name.
         * Lazy, so refusing at the first disagreement keeps this short, however common the name's
         * start is.
         */
        private fun candidates(groupKey: String) = sequence {
            for (end in MIN_KEY_LENGTH until groupKey.length) {
                val shorter = groupKey.substring(0, end)
                if (shorter in guideIdByKey) yield(guideIdByKey[shorter])
            }
            // The key isn't present, so binarySearch gives -(where it would go) - 1.
            var i = -sortedKeys.binarySearch(groupKey) - 1
            while (i < sortedKeys.size && sortedKeys[i].startsWith(groupKey)) {
                yield(guideIdByKey[sortedKeys[i]])
                i++
            }
        }
    }

    /** One guide, for callers and tests that only ever have one. */
    @JvmName("buildIndexOfOneGuide")
    fun buildIndex(aliases: Iterable<Pair<String, String>>): Index = Index(listOf(guideIndexOf(aliases)))

    /**
     * Builds the lookup from every `<channel>` element seen across every enabled guide.
     *
     * Both the display-name (`BBC One`) and the raw id (`bbc1.uk`) are indexed — providers
     * that *do* fill in `epg_channel_id` often use the id form, and it costs nothing to
     * accept either.
     *
     * Two channels of **one** guide sharing a key make it match nothing: that guide is telling
     * two channels apart ('BBC One London', 'BBC One Wales') and we can't. Two **different**
     * guides sharing a key are almost always naming the same channel with their own ids, and
     * refusing that would make turning on a second guide take channels' guides away. So the key
     * goes to one guide, the same one every run: the provider's own guide first, then the lowest
     * feed id. If that guide shares the key between two of its channels, the key matches nothing;
     * it doesn't fall through to a lower guide.
     */
    fun buildIndex(aliases: Iterable<GuideAlias>): Index {
        val byGuide = aliases.groupBy { it.feedId }.values
            .sortedWith(compareBy({ !it.first().fromProvider }, { it.first().feedId }))
        return Index(byGuide.map { guide -> guideIndexOf(guide.map { it.epgId to it.displayName }) })
    }

    private fun guideIndexOf(aliases: Iterable<Pair<String, String>>): GuideIndex {
        val guideIdByKey = HashMap<String, String?>()

        for ((epgId, name) in aliases) {
            for (candidate in listOf(name, epgId)) {
                // Full normalise, not bare groupKeyOf: free guides append 'HD' to display
                // names ('BBC One East HD'), which would otherwise never key-match a
                // provider's 'BBC ONE EAST'. normalize() strips the quality token.
                val key = ChannelNameNormalizer.normalize(candidate).groupKey
                if (key.length < MIN_KEY_LENGTH) continue
                // Two different channels of one guide normalising to the same key can't be trusted
                // for matching; drop the key rather than pick a side, and it stays dropped.
                if (key !in guideIdByKey) {
                    guideIdByKey[key] = epgId
                } else if (guideIdByKey[key] != epgId) {
                    guideIdByKey[key] = null
                }
            }
        }

        return GuideIndex(guideIdByKey)
    }

    /**
     * Below this, keys are too generic to mean anything — `e4` is real, but `tv`, `hd` and
     * single letters match everything. 2 keeps `e4`/`5usa`-style names alive.
     */
    private const val MIN_KEY_LENGTH = 2
}
