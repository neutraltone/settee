/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv

import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import app.opentv.ui.design.SetteeFonts
import app.opentv.ui.design.SetteeType
import app.opentv.ui.design.forLocale
import app.opentv.ui.design.forText
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import java.util.Locale
import org.junit.Test

/**
 * Young Serif has no Cyrillic, Greek or Vietnamese, so titles in those languages, and titles
 * written in them on any interface, switch to Alegreya Sans Bold (docs/LOOK.md, Other languages)
 * instead of mixing in a system serif.
 */
class TitleFaceTest {

    @Test
    fun `titles in languages Young Serif can't draw use Alegreya Sans Bold`() {
        for (language in listOf("ru", "uk", "bg", "el", "vi")) {
            val hero = SetteeType.Hero.forLocale(Locale.forLanguageTag(language))
            assertWithMessage(language).that(hero.fontFamily).isEqualTo(SetteeFonts.AlegreyaSans)
            assertWithMessage(language).that(hero.fontWeight).isEqualTo(FontWeight.Bold)
            assertWithMessage(language).that(hero.fontSize).isEqualTo(32.sp)
        }
    }

    @Test
    fun `titles in other languages stay in Young Serif`() {
        for (language in listOf("en-GB", "fr", "de", "pl", "zh", "ar")) {
            val title = SetteeType.ScreenTitle.forLocale(Locale.forLanguageTag(language))
            assertWithMessage(language).that(title).isEqualTo(SetteeType.ScreenTitle)
        }
    }

    @Test
    fun `text that isn't a title is the same in every language`() {
        assertThat(SetteeType.Body.forLocale(Locale.forLanguageTag("ru"))).isEqualTo(SetteeType.Body)
        assertThat(SetteeType.CardTitle.forLocale(Locale.forLanguageTag("el"))).isEqualTo(SetteeType.CardTitle)
    }

    private val english = Locale.forLanguageTag("en-GB")

    @Test
    fun `Cyrillic, Greek and Vietnamese titles on an English interface use Alegreya Sans Bold`() {
        for (title in listOf("Бриллиантовая рука", "Ο Θίασος", "Mùa hè chiều thẳng đứng", "Ơn trời")) {
            val hero = SetteeType.Hero.forText(title, english)
            assertWithMessage(title).that(hero.fontFamily).isEqualTo(SetteeFonts.AlegreyaSans)
            assertWithMessage(title).that(hero.fontWeight).isEqualTo(FontWeight.Bold)
            assertWithMessage(title).that(hero.fontSize).isEqualTo(32.sp)
        }
    }

    @Test
    fun `a Latin title on an English interface stays in Young Serif, accents and all`() {
        for (title in listOf("The Quiet Road", "Amélie", "Łódź Nights", "Smörgåsbord 2", "")) {
            assertWithMessage(title).that(SetteeType.Display.forText(title, english)).isEqualTo(SetteeType.Display)
        }
    }

    @Test
    fun `a title in a script neither face draws stays in Young Serif`() {
        for (title in listOf("七人の侍", "باب الحديد")) {
            assertWithMessage(title).that(SetteeType.Hero.forText(title, english)).isEqualTo(SetteeType.Hero)
        }
    }

    @Test
    fun `the interface language still decides for a Latin title`() {
        val title = SetteeType.Hero.forText("The Quiet Road", Locale.forLanguageTag("ru"))
        assertThat(title.fontFamily).isEqualTo(SetteeFonts.AlegreyaSans)
    }

    @Test
    fun `text that isn't a title keeps its face whatever it says`() {
        assertThat(SetteeType.Body.forText("Бриллиантовая рука", english)).isEqualTo(SetteeType.Body)
    }
}
