/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv

import app.opentv.data.parser.parseReleaseDate
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class ReleaseDateTest {

    @Test
    fun `a full date is that day at midnight UTC`() {
        assertThat(parseReleaseDate("2024-03-15")).isEqualTo(1_710_460_800_000L)
    }

    @Test
    fun `a date with a time after it is still that day`() {
        assertThat(parseReleaseDate("2011-09-02 00:00:00")).isEqualTo(1_314_921_600_000L)
    }

    @Test
    fun `a year on its own is too coarse to rank by, so it is undated`() {
        assertThat(parseReleaseDate("2024")).isNull()
    }

    @Test
    fun `a missing, blank or zeroed date is undated`() {
        assertThat(parseReleaseDate(null)).isNull()
        assertThat(parseReleaseDate("")).isNull()
        assertThat(parseReleaseDate("0000-00-00")).isNull()
        assertThat(parseReleaseDate("soon")).isNull()
    }
}
