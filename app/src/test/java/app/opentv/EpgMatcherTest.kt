/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv

import app.opentv.data.parser.ChannelNameNormalizer
import app.opentv.data.repo.EpgMatcher
import com.google.common.truth.Truth.assertThat
import kotlin.random.Random
import org.junit.Test

/**
 * The matcher's contract: prefer no match to a wrong match. A missing guide entry is an
 * annoyance with a manual override; a wrong one convinces the user the whole guide lies.
 */
class EpgMatcherTest {

    /** Shorthand: build an index from (epgId, displayName) pairs. */
    private fun index(vararg aliases: Pair<String, String>) = EpgMatcher.buildIndex(aliases.toList())

    private fun keyOf(providerName: String) = ChannelNameNormalizer.normalize(providerName).groupKey

    @Test
    fun `provider channel matches guide channel by normalised name`() {
        val idx = index("bbc1.uk" to "BBC One", "itv1.uk" to "ITV1")

        assertThat(idx.match(keyOf("UK| BBC ONE FHD"))).isEqualTo("bbc1.uk")
        assertThat(idx.match(keyOf("UK - ITV 1 HD"))).isEqualTo("itv1.uk")
    }

    @Test
    fun `the raw guide id is matchable too`() {
        // Providers that do fill in epg_channel_id often use the id form directly.
        val idx = index("bbc1.uk" to "BBC One")

        assertThat(idx.match(ChannelNameNormalizer.groupKeyOf("bbc1.uk"))).isEqualTo("bbc1.uk")
    }

    @Test
    fun `unique prefix matches are accepted`() {
        val idx = index("bbc1london.uk" to "BBC One London")

        // Provider says just "BBC One"; the only candidate is the London region — take it.
        assertThat(idx.match(keyOf("UK| BBC ONE"))).isEqualTo("bbc1london.uk")
    }

    @Test
    fun `ambiguous prefix matches are refused`() {
        val idx = index(
            "bbc1london.uk" to "BBC One London",
            "bbc1wales.uk" to "BBC One Wales",
        )

        // Two regions fit and nothing exact does. Guessing here shows the wrong regional
        // news against the channel — refuse, and let the manual override decide.
        assertThat(idx.match(keyOf("UK| BBC ONE"))).isNull()
    }

    @Test
    fun `colliding display names disable exact matching for that key`() {
        val idx = index(
            "cnn.us" to "CNN",
            "cnn.int" to "CNN",
        )

        assertThat(idx.match(keyOf("CNN"))).isNull()
    }

    @Test
    fun `unknown channels match nothing`() {
        val idx = index("bbc1.uk" to "BBC One")

        assertThat(idx.match(keyOf("UK| SOME SHOP CHANNEL"))).isNull()
    }

    @Test
    fun `keys too short to mean anything never match`() {
        val idx = index("e.uk" to "E")

        assertThat(idx.match("e")).isNull()
        // But two characters is a real channel name in Britain.
        val e4 = index("e4.uk" to "E4")
        assertThat(e4.match(keyOf("UK| E4 HD"))).isEqualTo("e4.uk")
    }

    @Test
    fun `end to end - the exact names from the reporting provider`() {
        // The names that started all this, verbatim from the screen.
        val idx = index(
            "bbc1.uk" to "BBC One",
            "bbc2.uk" to "BBC Two",
            "channel4.uk" to "Channel 4",
            "skyatlantic.uk" to "Sky Atlantic",
        )

        assertThat(idx.match(keyOf("UK| BBC ONE HD/RAW"))).isEqualTo("bbc1.uk")
        assertThat(idx.match(keyOf("UK| BBC TWO FHD"))).isEqualTo("bbc2.uk")
        assertThat(idx.match(keyOf("UK| CHANNEL 4 hevc"))).isEqualTo("channel4.uk")
        assertThat(idx.match(keyOf("UK| SKY ATLANTIC ᴴᴰ"))).isEqualTo("skyatlantic.uk")
    }

    @Test
    fun `a provider name that extends a guide name matches it`() {
        val idx = index("one.uk" to "Ochre Valley")

        assertThat(idx.match("ochrevalleywest")).isEqualTo("one.uk")
    }

    @Test
    fun `an exact match beats longer guide names that would otherwise be ambiguous`() {
        val idx = index(
            "plain.uk" to "Ochre",
            "west.uk" to "Ochre West",
            "east.uk" to "Ochre East",
        )

        assertThat(idx.match("ochre")).isEqualTo("plain.uk")
    }

    @Test
    fun `candidates shorter and longer than the provider name must agree`() {
        val idx = index("short.uk" to "Ochre", "long.uk" to "Ochre Valley West")

        assertThat(idx.match("ochrevalley")).isNull()
    }

    @Test
    fun `two guide names that are both prefixes of the provider name must agree`() {
        val idx = index("short.uk" to "Ochre", "long.uk" to "Ochre Valley")

        assertThat(idx.match("ochrevalleywest")).isNull()
    }

    @Test
    fun `every candidate naming one guide channel still counts as agreement`() {
        // The channel's id and its name are both aliases, and both fit here.
        val idx = index("ochrevalleywest" to "Ochre Valley")

        assertThat(idx.match("ochrevalleyw")).isEqualTo("ochrevalleywest")
        assertThat(idx.match("ochre")).isEqualTo("ochrevalleywest")
    }

    @Test
    fun `a name two guide channels share is refused in the prefix tier too`() {
        val idx = index("a.uk" to "Ochre", "b.uk" to "Ochre")

        assertThat(idx.match("ochrevalley")).isNull()
        assertThat(idx.match("och")).isNull()
    }

    @Test
    fun `a name two guide channels share stays refused when one of them repeats`() {
        val idx = index("a.uk" to "Ochre", "b.uk" to "Ochre", "a.uk" to "Ochre")

        assertThat(idx.match("ochre")).isNull()
    }

    @Test
    fun `prefixes of two characters count but single characters never do`() {
        assertThat(index("e4.uk" to "E4").match("e4extra")).isEqualTo("e4.uk")
        assertThat(index("x.uk" to "E").match("e4")).isNull()
        assertThat(index("x.uk" to "Ochre").match("o")).isNull()
        assertThat(index("x.uk" to "Ochre").match("")).isNull()
    }

    @Test
    fun `a guide with no channels matches nothing`() {
        assertThat(index().match("ochre")).isNull()
    }

    /**
     * The rule the prefix tier has to keep, written out the slow way: every guide name is checked,
     * so this can't share a mistake with a faster lookup. A small alphabet makes names run into
     * each other far more often than real ones, which is where the two could disagree.
     */
    @Test
    fun `results match checking every guide name in turn`() {
        val random = Random(SEED)
        fun name(maxParts: Int) = (1..random.nextInt(1, maxParts + 1)).joinToString("") { PARTS.random(random) }
        var exact = 0
        var byPrefix = 0
        var refused = 0
        var unmatched = 0

        repeat(GUIDES) {
            val aliases = List(random.nextInt(0, 60)) { name(4) to name(5) }
            val idx = EpgMatcher.buildIndex(aliases)
            val keys = aliases.flatMap { (id, guideName) ->
                listOf(id to keyOfGuide(guideName), id to keyOfGuide(id))
            }
            val queries = keys.flatMap { (_, key) ->
                listOf(key, key.dropLast(1), key.take(2), key.take(1), key + name(2))
            } + List(QUERIES_PER_GUIDE) { name(6) }

            for (query in queries) {
                val expected = everyNameInTurn(keys, query)
                assertThat(idx.match(query)).isEqualTo(expected.result)
                when (expected.kind) {
                    Kind.EXACT -> exact++
                    Kind.PREFIX -> byPrefix++
                    Kind.REFUSED -> refused++
                    Kind.NONE -> unmatched++
                }
            }
        }

        // Every path has to have been taken many times, or this proves nothing.
        assertThat(exact).isGreaterThan(MIN_CASES_PER_KIND)
        assertThat(byPrefix).isGreaterThan(MIN_CASES_PER_KIND)
        assertThat(refused).isGreaterThan(MIN_CASES_PER_KIND)
        assertThat(unmatched).isGreaterThan(MIN_CASES_PER_KIND)
    }

    private enum class Kind { EXACT, PREFIX, REFUSED, NONE }

    private data class Expected(val result: String?, val kind: Kind)

    private fun keyOfGuide(name: String) = ChannelNameNormalizer.normalize(name).groupKey

    private fun everyNameInTurn(keys: List<Pair<String, String>>, query: String): Expected {
        val usable = keys.filter { (_, key) -> key.length >= 2 }
        val sameKey = usable.filter { (_, key) -> key == query }.map { it.first }.distinct()
        val fits = usable
            .filter { (_, key) -> key.startsWith(query) || query.startsWith(key) }
            .map { it.first }
            .distinct()
        return when {
            query.length < 2 || fits.isEmpty() -> Expected(null, Kind.NONE)
            sameKey.size == 1 -> Expected(sameKey.single(), Kind.EXACT)
            fits.size == 1 -> Expected(fits.single(), Kind.PREFIX)
            else -> Expected(null, Kind.REFUSED)
        }
    }

    private companion object {
        const val SEED = 177
        const val GUIDES = 300
        const val QUERIES_PER_GUIDE = 40
        const val MIN_CASES_PER_KIND = 1_000
        val PARTS = listOf("a", "b", "ab", "ba", "c", "cab")
    }
}
