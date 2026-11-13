/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv

import app.opentv.data.repo.VersionTraits
import app.opentv.data.repo.versionTraits
import com.google.common.truth.Truth.assertThat
import org.junit.Test

/** What a Version is, as its name and category say: what the Quality pill tells apart (#181). */
class VersionTraitsTest {

    @Test
    fun `a 4K shelf is a 4K Version, and an untagged one's Quality is unknown`() {
        assertThat(versionTraits("4K-NF - Superbad (2007)", null).qualityLabel).isEqualTo("4K")
        assertThat(versionTraits("NF - Superbad (2007)", null).qualityRank).isEqualTo(0)
    }

    @Test
    fun `the shelf before the title says its language`() {
        assertThat(versionTraits("DE - Superbad (2007)", null).language).isEqualTo("de")
        assertThat(versionTraits("4K-EN - Superbad (2007)", null).language).isEqualTo("en")
        assertThat(versionTraits("NF - Superbad (2007)", null).language).isNull()
    }

    @Test
    fun `a title that starts like a shelf code isn't one`() {
        assertThat(versionTraits("It - Chapter Two (2019)", null).language).isNull()
    }

    @Test
    fun `a film named after a language or a camera is neither`() {
        assertThat(versionTraits("EN - The Italian Job (2003)", null).language).isNull()
        assertThat(versionTraits("THE ITALIAN JOB (2003)", null).language).isNull()
        assertThat(versionTraits("EN - Cam (2018)", null).cam).isFalse()
        assertThat(versionTraits("EN - The Dub (2020)", null).dubbed).isFalse()
    }

    @Test
    fun `a marker in capitals after a title says what it is`() {
        assertThat(versionTraits("Il Sorpasso (1962) ITALIAN SUB", null).subtitles).isEqualTo("it")
        assertThat(versionTraits("NEW FILM (2026) [CAM]", null).cam).isTrue()
    }

    @Test
    fun `a country after the title is where it's from, not its language`() {
        assertThat(versionTraits("NF - Love 101 (2020) (TR)", null).language).isNull()
    }

    @Test
    fun `a category's words say its language and subtitles`() {
        val traits = versionTraits("Il Sorpasso (1962)", "Italian, English subtitles")

        assertThat(traits.language).isEqualTo("it")
        assertThat(traits.subtitles).isEqualTo("en")
        assertThat(versionTraits("Superbad", "GERMAN MOVIES").language).isEqualTo("de")
    }

    @Test
    fun `a dubbed or subtitled Version is marked even without a language`() {
        assertThat(versionTraits("Superbad DUB", null).dubbed).isTrue()
        assertThat(versionTraits("TOP - Superbad", "TOP MOVIES MULTI-SUBTITLES").subtitles).isEqualTo("multi")
    }

    @Test
    fun `Dolby sound and cam copies are marked from the name or the category`() {
        assertThat(versionTraits("NF - Superbad", "NETFLIX MOVIES DOLBY AUDIO").sound).isEqualTo("Dolby")
        assertThat(versionTraits("EN - Superbad (2007) Atmos", null).sound).isEqualTo("Atmos")
        assertThat(versionTraits("EN - New Film (2026) HDCAM", null).cam).isTrue()
        assertThat(versionTraits("New Film (2026)", "CAM MOVIES").cam).isTrue()
        assertThat(versionTraits("EN - Camera Buff (1979)", null).cam).isFalse()
    }

    @Test
    fun `two copies on different shelves with nothing else between them are the same`() {
        val netflix = versionTraits("NF - Superbad (2007)", "NETFLIX MOVIES")
        val amazon = versionTraits("AMZ - Superbad (2007)", "AMAZON MOVIES")

        assertThat(netflix).isEqualTo(amazon)
        assertThat(netflix).isEqualTo(VersionTraits())
    }

    @Test
    fun `a Version in the device's language, or unmarked, needs no asking`() {
        assertThat(versionTraits("EN - Superbad", null).inLanguage("en")).isTrue()
        assertThat(versionTraits("NF - Superbad", null).inLanguage("en")).isTrue()
        assertThat(versionTraits("DE - Superbad", null).inLanguage("en")).isFalse()
        assertThat(versionTraits("Superbad DUB", null).inLanguage("en")).isFalse()
        assertThat(versionTraits("Il Sorpasso", "Italian, English subtitles").inLanguage("en")).isFalse()
        assertThat(versionTraits("TOP - Superbad", "MULTI-SUBTITLES").inLanguage("en")).isTrue()
    }
}
