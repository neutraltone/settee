/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv

import app.opentv.data.parser.ChannelNameNormalizer
import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * Every case in here is a naming style seen on a real provider. When a channel groups or
 * matches wrongly in the wild, the fix starts with a new test named after it.
 */
class ChannelNameNormalizerTest {

    @Test
    fun `strips a UK pipe prefix and an FHD tag`() {
        val n = ChannelNameNormalizer.normalize("UK| BBC ONE FHD")

        assertThat(n.baseName).isEqualTo("BBC ONE")
        assertThat(n.groupKey).isEqualTo("bbc1")
        assertThat(n.qualityRank).isEqualTo(300)
        assertThat(n.qualityLabel).isEqualTo("FHD")
        assertThat(n.region).isEqualTo("UK")
    }

    @Test
    fun `a 4K tag glued to a provider's shelf still says 4K`() {
        val glued = ChannelNameNormalizer.normalize("4K-TOP - Superbad (2007)")

        assertThat(glued.qualityRank).isEqualTo(ChannelNameNormalizer.normalize("Superbad 4K").qualityRank)
        assertThat(glued.qualityLabel).isEqualTo("4K")
        assertThat(glued.baseName).doesNotContain("4K")
    }

    @Test
    fun `a 4K-sized tag other than 4K or 8K at the front of a hyphenated name is part of the name`() {
        val name = ChannelNameNormalizer.normalize("Fuhd-1")

        assertThat(name.qualityRank).isEqualTo(0)
        assertThat(name.baseName).isEqualTo("Fuhd-1")
    }

    @Test
    fun `a lower tag at the front of a hyphenated name is part of the name`() {
        val name = ChannelNameNormalizer.normalize("Sd-Sport")

        assertThat(name.qualityRank).isEqualTo(0)
        assertThat(name.baseName).isEqualTo("Sd-Sport")
    }

    @Test
    fun `quality variants of one channel share a group key`() {
        val variants = listOf(
            "UK| BBC ONE SD",
            "UK| BBC ONE HD",
            "UK| BBC ONE FHD",
            "UK - BBC One UHD",
        ).map { ChannelNameNormalizer.normalize(it) }

        assertThat(variants.map { it.groupKey }.distinct()).hasSize(1)
        // And ranks order them correctly for "best plays by default".
        assertThat(variants.map { it.qualityRank }).isEqualTo(listOf(100, 200, 300, 400))
    }

    @Test
    fun `provider name meets the guide name in the middle`() {
        val provider = ChannelNameNormalizer.normalize("UK| BBC ONE FHD").groupKey
        val guide = ChannelNameNormalizer.groupKeyOf("BBC One")

        assertThat(provider).isEqualTo(guide)
    }

    @Test
    fun `word and digit channel numbers fold together`() {
        assertThat(ChannelNameNormalizer.groupKeyOf("BBC One"))
            .isEqualTo(ChannelNameNormalizer.groupKeyOf("BBC 1"))
        assertThat(ChannelNameNormalizer.groupKeyOf("Channel Four"))
            .isEqualTo(ChannelNameNormalizer.groupKeyOf("Channel 4"))
    }

    @Test
    fun `RAW and fps markers strip into the label without ranking`() {
        val n = ChannelNameNormalizer.normalize("UK| PRIME RAW 60fps")

        assertThat(n.baseName).isEqualTo("PRIME")
        assertThat(n.qualityRank).isEqualTo(0)
        assertThat(n.qualityLabel).isEqualTo("RAW 60fps")
    }

    @Test
    fun `hash decorations are stripped`() {
        val n = ChannelNameNormalizer.normalize("####  PRIME RAW 60fps #####")

        assertThat(n.baseName).isEqualTo("PRIME")
    }

    @Test
    fun `bracketed country prefixes work too`() {
        assertThat(ChannelNameNormalizer.normalize("[UK] ITV 1 HD").region).isEqualTo("UK")
        assertThat(ChannelNameNormalizer.normalize("(US) CNN HD").region).isEqualTo("US")
        assertThat(ChannelNameNormalizer.normalize("USA - ESPN").region).isEqualTo("USA")
    }

    @Test
    fun `superscript HD decorations are understood`() {
        val n = ChannelNameNormalizer.normalize("UK| SKY ATLANTIC ᴴᴰ")

        assertThat(n.baseName).isEqualTo("SKY ATLANTIC")
        assertThat(n.qualityRank).isEqualTo(200)
    }

    @Test
    fun `broadcaster names that look like prefixes are left alone`() {
        // Four-letter and pipe-less names must not lose their first word.
        assertThat(ChannelNameNormalizer.normalize("BEIN| SPORTS 1 HD").baseName)
            .contains("SPORTS")
        val sky = ChannelNameNormalizer.normalize("Sky Sports Main Event")
        assertThat(sky.baseName).isEqualTo("Sky Sports Main Event")
        assertThat(sky.region).isNull()
    }

    @Test
    fun `hevc and h265 are stream tags not part of the name`() {
        val n = ChannelNameNormalizer.normalize("UK| GENERAL hevc")

        assertThat(n.baseName).isEqualTo("GENERAL")
        assertThat(n.qualityLabel).isEqualTo("hevc")
    }

    @Test
    fun `a name that is nothing but junk still yields a usable key`() {
        val n = ChannelNameNormalizer.normalize("HD")

        // Everything stripped: fall back to the raw name rather than an empty key that
        // would group all such channels into one giant accidental family.
        assertThat(n.groupKey).isNotEmpty()
    }

    @Test
    fun `group keys ignore case punctuation and spacing`() {
        val keys = listOf("BBC One", "bbc-one", "B B C ONE", "BbcOne")
            .map { ChannelNameNormalizer.groupKeyOf(it) }

        assertThat(keys.distinct()).hasSize(1)
    }
}
