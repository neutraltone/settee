/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv.ui

import com.google.common.truth.Truth.assertThat
import java.util.Locale
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

// Robolectric for android.icu, which Android's own grapheme breaking comes from.
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class ProfileInitialTest {

    private val english = Locale.ENGLISH

    @Test
    fun `a plain name gives its first letter in capitals`() {
        assertThat(profileInitial("tony", english)).isEqualTo("T")
    }

    @Test
    fun `a name starting with an emoji gives the whole emoji`() {
        assertThat(profileInitial("😀Ana", english)).isEqualTo("😀")
    }

    @Test
    fun `an emoji with a skin tone stays whole`() {
        assertThat(profileInitial("👍🏽Bo", english)).isEqualTo("👍🏽")
    }

    @Test
    fun `a flag stays whole`() {
        assertThat(profileInitial("🇬🇧Jo", english)).isEqualTo("🇬🇧")
    }

    @Test
    fun `a Turkish name capitalises a dotted i in Turkish`() {
        assertThat(profileInitial("ilker", Locale.forLanguageTag("tr"))).isEqualTo("İ")
    }

    @Test
    fun `an empty name gives no initial`() {
        assertThat(profileInitial("", english)).isEmpty()
    }
}
