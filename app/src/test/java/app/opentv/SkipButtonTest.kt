/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv

import app.opentv.player.SkipButton
import app.opentv.player.SkipCue.Kind
import app.opentv.player.SkipCue.Stretch
import com.google.common.truth.Truth.assertThat
import org.junit.Test

/** When Skip intro shows (#518): through the first 10 seconds of the intro, and while the controls are up. */
class SkipButtonTest {

    private val intro = Stretch(Kind.INTRO, fromMillis = 60_000, toMillis = 120_000)
    private val button = SkipButton().apply { stretches = listOf(intro) }

    @Test
    fun `nothing shows before the intro`() {
        assertThat(button.update(positionMillis = 59_999, controlsShowing = false)).isNull()
    }

    @Test
    fun `the button shows as the intro starts`() {
        assertThat(button.update(positionMillis = 60_000, controlsShowing = false)).isEqualTo(intro)
    }

    @Test
    fun `the button goes 10 seconds after it came up`() {
        button.update(positionMillis = 60_000, controlsShowing = false)

        assertThat(button.update(positionMillis = 69_999, controlsShowing = false)).isEqualTo(intro)
        assertThat(button.update(positionMillis = 70_000, controlsShowing = false)).isNull()
    }

    @Test
    fun `the button goes when the intro ends, even inside its 10 seconds`() {
        val short = Stretch(Kind.INTRO, fromMillis = 60_000, toMillis = 65_000)
        button.stretches = listOf(short)
        button.update(positionMillis = 60_000, controlsShowing = false)

        assertThat(button.update(positionMillis = 65_000, controlsShowing = false)).isNull()
    }

    @Test
    fun `bringing up the controls during the intro brings the button back`() {
        button.update(positionMillis = 60_000, controlsShowing = false)
        button.update(positionMillis = 75_000, controlsShowing = false)

        assertThat(button.update(positionMillis = 80_000, controlsShowing = true)).isEqualTo(intro)
        assertThat(button.update(positionMillis = 81_000, controlsShowing = false)).isNull()
    }

    @Test
    fun `the controls don't bring the button up outside the intro`() {
        assertThat(button.update(positionMillis = 120_000, controlsShowing = true)).isNull()
    }

    @Test
    fun `arriving in the middle of the intro counts 10 seconds from where playback arrived`() {
        assertThat(button.update(positionMillis = 100_000, controlsShowing = false)).isEqualTo(intro)
        assertThat(button.update(positionMillis = 109_999, controlsShowing = false)).isEqualTo(intro)
        assertThat(button.update(positionMillis = 110_000, controlsShowing = false)).isNull()
    }

    @Test
    fun `going back before the intro and playing into it again brings the button up again`() {
        button.update(positionMillis = 60_000, controlsShowing = false)
        button.update(positionMillis = 75_000, controlsShowing = false)
        button.update(positionMillis = 30_000, controlsShowing = false)

        assertThat(button.update(positionMillis = 60_500, controlsShowing = false)).isEqualTo(intro)
    }

    @Test
    fun `each stretch gets its own 10 seconds`() {
        val recap = Stretch(Kind.RECAP, fromMillis = 0, toMillis = 30_000)
        button.stretches = listOf(recap, intro)
        button.update(positionMillis = 0, controlsShowing = false)
        button.update(positionMillis = 15_000, controlsShowing = false)

        assertThat(button.update(positionMillis = 61_000, controlsShowing = false)).isEqualTo(intro)
    }

    @Test
    fun `new stretches start afresh`() {
        button.update(positionMillis = 60_000, controlsShowing = false)
        button.update(positionMillis = 75_000, controlsShowing = false)

        button.stretches = listOf(intro)

        assertThat(button.update(positionMillis = 76_000, controlsShowing = false)).isEqualTo(intro)
    }

    @Test
    fun `with no stretches nothing shows`() {
        button.stretches = emptyList()

        assertThat(button.update(positionMillis = 60_000, controlsShowing = true)).isNull()
    }
}
