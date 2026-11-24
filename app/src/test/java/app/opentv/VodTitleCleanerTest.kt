/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv

import app.opentv.data.parser.VodTitleCleaner
import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * Raw provider titles in, the title a viewer sees (and TMDB is searched for) out. Every case is a
 * naming style seen on a real provider, with made-up or well-known titles.
 */
class VodTitleCleanerTest {

    @Test
    fun `a film starting with It keeps its first word`() {
        assertThat(VodTitleCleaner.clean("It: Welcome to Derry")).isEqualTo("It: Welcome to Derry")
    }

    @Test
    fun `It after a language tag survives the tag being stripped`() {
        assertThat(VodTitleCleaner.clean("EN - It - Chapter Two (2019)")).isEqualTo("It - Chapter Two (2019)")
    }

    @Test
    fun `films starting with No, In or Id keep their first word`() {
        assertThat(VodTitleCleaner.clean("No - Country Road (2020)")).isEqualTo("No - Country Road (2020)")
        assertThat(VodTitleCleaner.clean("In: The Heights")).isEqualTo("In: The Heights")
        assertThat(VodTitleCleaner.clean("Id - Forbidden Planet")).isEqualTo("Id - Forbidden Planet")
    }

    @Test
    fun `an upper-case language tag is still stripped`() {
        assertThat(VodTitleCleaner.clean("IT - Gomorra")).isEqualTo("Gomorra")
        assertThat(VodTitleCleaner.clean("NO | Nordic Noir")).isEqualTo("Nordic Noir")
        assertThat(VodTitleCleaner.clean("[IN] Bollywood Nights")).isEqualTo("Bollywood Nights")
    }

    @Test
    fun `quality and service tags still come off the front`() {
        assertThat(VodTitleCleaner.clean("4K-EN - Barbie (2023)")).isEqualTo("Barbie (2023)")
        assertThat(VodTitleCleaner.clean("NF - Our Sticky Love (2026) (KR)")).isEqualTo("Our Sticky Love (2026)")
    }
}
