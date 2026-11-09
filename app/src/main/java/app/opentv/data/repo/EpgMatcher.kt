/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv.data.repo

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
 */
object EpgMatcher {

    /**
     * @param guideIdByKey Every guide key and the one guide channel it names, or null when two
     *   channels share it.
     */
    class Index internal constructor(private val guideIdByKey: Map<String, String?>) {
        /** In order, because keys extending a name sit next to each other. */
        private val sortedKeys = guideIdByKey.keys.sorted().toTypedArray()

        /** Returns the epg id for a provider channel's group key, or null. */
        fun match(groupKey: String): String? {
            if (groupKey.length < MIN_KEY_LENGTH) return null

            // A shared key is refused outright: both its channels would be candidates below.
            if (groupKey in guideIdByKey) return guideIdByKey[groupKey]

            // Prefix tier: the provider name extends the guide name or vice versa
            // ("skysportsmainevent" vs "skysportsmainevt"; "bbcone" vs "bbconelondon").
            // Only accepted when every candidate agrees on a single epg id.
            //
            // Not by checking each guide key in turn: that's channels x aliases, and it took most of
            // the matcher's time on a Fire TV Stick (#177).
            var found: String? = null
            for (end in MIN_KEY_LENGTH until groupKey.length) {
                val shorter = groupKey.substring(0, end)
                if (shorter !in guideIdByKey) continue
                val id = guideIdByKey[shorter] ?: return null
                if (found != null && found != id) return null
                found = id
            }
            // The key isn't present, so binarySearch gives -(where it would go) - 1. Refusing at the
            // first disagreement keeps this run short, however common the name's start is.
            var i = -sortedKeys.binarySearch(groupKey) - 1
            while (i < sortedKeys.size && sortedKeys[i].startsWith(groupKey)) {
                val id = guideIdByKey[sortedKeys[i]] ?: return null
                if (found != null && found != id) return null
                found = id
                i++
            }
            return found
        }
    }

    /**
     * Builds the lookup from every `<channel>` element seen across every enabled guide.
     *
     * Both the display-name (`BBC One`) and the raw id (`bbc1.uk`) are indexed — providers
     * that *do* fill in `epg_channel_id` often use the id form, and it costs nothing to
     * accept either.
     */
    fun buildIndex(aliases: Iterable<Pair<String, String>>): Index {
        val guideIdByKey = HashMap<String, String?>()

        for ((epgId, name) in aliases) {
            for (candidate in listOf(name, epgId)) {
                // Full normalise, not bare groupKeyOf: free guides append 'HD' to display
                // names ('BBC One East HD'), which would otherwise never key-match a
                // provider's 'BBC ONE EAST'. normalize() strips the quality token.
                val key = ChannelNameNormalizer.normalize(candidate).groupKey
                if (key.length < MIN_KEY_LENGTH) continue
                // Two different guide channels normalising to the same key can't be trusted for
                // matching; drop the key rather than pick a side, and it stays dropped.
                if (key !in guideIdByKey) {
                    guideIdByKey[key] = epgId
                } else if (guideIdByKey[key] != epgId) {
                    guideIdByKey[key] = null
                }
            }
        }

        return Index(guideIdByKey)
    }

    /**
     * Below this, keys are too generic to mean anything — `e4` is real, but `tv`, `hd` and
     * single letters match everything. 2 keeps `e4`/`5usa`-style names alive.
     */
    private const val MIN_KEY_LENGTH = 2
}
