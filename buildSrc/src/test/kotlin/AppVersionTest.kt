/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
import com.google.common.truth.Truth.assertThat
import org.junit.Assert.assertThrows
import org.junit.Test

class AppVersionTest {

    @Test
    fun `the first release is version code 100`() {
        assertThat(versionCodeOf("0.1.0")).isEqualTo(100)
    }

    @Test
    fun `each part takes its own two digits`() {
        assertThat(versionCodeOf("1.2.3")).isEqualTo(10203)
        assertThat(versionCodeOf("0.10.0")).isGreaterThan(versionCodeOf("0.9.99"))
    }

    @Test
    fun `a minor or patch of 100 would collide with the next part, so it is refused`() {
        assertThrows(IllegalArgumentException::class.java) { versionCodeOf("0.100.0") }
        assertThrows(IllegalArgumentException::class.java) { versionCodeOf("0.1.100") }
    }

    @Test
    fun `a name that is not major-minor-patch is refused with the expected format`() {
        for (name in listOf("1.0", "0.2.0-rc1", "v0.1.0", "")) {
            val e = assertThrows(IllegalArgumentException::class.java) { versionCodeOf(name) }
            assertThat(e).hasMessageThat().contains("major.minor.patch")
        }
    }
}
