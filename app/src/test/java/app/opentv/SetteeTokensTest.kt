/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv

import androidx.compose.foundation.shape.CornerBasedShape
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.opentv.ui.design.SetteeColors
import app.opentv.ui.design.SetteeFonts
import app.opentv.ui.design.SetteeShapes
import app.opentv.ui.design.SetteeSpace
import app.opentv.ui.design.SetteeType
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import org.junit.Test

/** Every expected value here is copied from docs/LOOK.md's Tokens tables, not from the code. */
class SetteeTokensTest {

    @Test
    fun `colours match LOOK md`() {
        assertThat(SetteeColors.Background).isEqualTo(Color(0xFF1A1416))
        assertThat(SetteeColors.Surface).isEqualTo(Color(0xFF281F22))
        assertThat(SetteeColors.Raised).isEqualTo(Color(0xFF362A2E))
        assertThat(SetteeColors.Rule).isEqualTo(Color(0xFF45373B))
        assertThat(SetteeColors.Text).isEqualTo(Color(0xFFF8EFEA))
        assertThat(SetteeColors.QuietText).isEqualTo(Color(0xFFBDABA8))
        assertThat(SetteeColors.Accent).isEqualTo(Color(0xFFFF8F6B))
        assertThat(SetteeColors.OnAccent).isEqualTo(Color(0xFF2A130B))
    }

    @Test
    fun `type roles match LOOK md`() {
        assertRole(SetteeType.Display, SetteeFonts.YoungSerif, FontWeight.Normal, 54)
        assertRole(SetteeType.Hero, SetteeFonts.YoungSerif, FontWeight.Normal, 32)
        assertRole(SetteeType.ScreenTitle, SetteeFonts.YoungSerif, FontWeight.Normal, 28)
        assertRole(SetteeType.Section, SetteeFonts.YoungSerif, FontWeight.Normal, 18)
        assertRole(SetteeType.Synopsis, SetteeFonts.AlegreyaSans, FontWeight.Medium, 18)
        assertRole(SetteeType.CardTitle, SetteeFonts.AlegreyaSans, FontWeight.Bold, 16)
        assertRole(SetteeType.RowTitle, SetteeFonts.AlegreyaSans, FontWeight.Bold, 17)
        assertRole(SetteeType.Body, SetteeFonts.AlegreyaSans, FontWeight.Medium, 16)
    }

    @Test
    fun `no type role is under 16 sp`() {
        val roles = typeRoles()
        assertThat(roles).isNotEmpty()
        roles.forEach { (name, style) ->
            assertWithMessage(name).that(style.fontSize.value).isAtLeast(16f)
        }
    }

    @Test
    fun `every type role draws lining tabular figures`() {
        typeRoles().forEach { (name, style) ->
            assertWithMessage(name).that(style.fontFeatureSettings).isEqualTo("lnum, tnum")
        }
    }

    @Test
    fun `corners match LOOK md`() {
        assertThat(cornerDp(SetteeShapes.Cell)).isEqualTo(2f)
        assertThat(cornerDp(SetteeShapes.Thumbnail)).isEqualTo(7f)
        assertThat(cornerDp(SetteeShapes.Card)).isEqualTo(10f)
        // A pill's corners are half its height, whatever that is.
        assertThat(cornerDp(SetteeShapes.Pill, height = 40f)).isEqualTo(20f)
        assertThat(cornerDp(SetteeShapes.Pill, height = 56f)).isEqualTo(28f)
    }

    @Test
    fun `space matches LOOK md`() {
        assertThat(SetteeSpace.ScreenSide).isEqualTo(48.dp)
        assertThat(SetteeSpace.ScreenTopBottom).isEqualTo(27.dp)
        assertThat(SetteeSpace.CardGap).isEqualTo(12.dp)
        assertThat(SetteeSpace.RowGap).isEqualTo(14.dp)
    }

    /** Every corner of [shape] in dp, on a 200-dp-wide box [height] dp tall, at density 1. */
    private fun cornerDp(shape: CornerBasedShape, height: Float = 100f): Float {
        val size = Size(200f, height)
        val density = Density(1f)
        val corners = listOf(shape.topStart, shape.topEnd, shape.bottomEnd, shape.bottomStart)
            .map { it.toPx(size, density) }
        assertThat(corners.toSet()).hasSize(1)
        return corners.first()
    }

    private fun assertRole(style: TextStyle, family: FontFamily, weight: FontWeight, sizeSp: Int) {
        assertThat(style.fontFamily).isEqualTo(family)
        assertThat(style.fontWeight).isEqualTo(weight)
        assertThat(style.fontSize).isEqualTo(sizeSp.sp)
    }

    /** Found by reflection, so a role added later can't miss the checks. */
    private fun typeRoles(): List<Pair<String, TextStyle>> = SetteeType::class.java.declaredMethods
        .filter { it.returnType == TextStyle::class.java && it.parameterCount == 0 }
        .map { it.name to it.invoke(SetteeType) as TextStyle }
}
