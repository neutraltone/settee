/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv

import app.opentv.ui.vod.UnfinishedSeek
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class UnfinishedSeekTest {

    private val seek = UnfinishedSeek()

    @Test
    fun `letting go of a key that wasn't pressed on the bar seeks nowhere`() {
        assertThat(seek.released()).isNull()
    }

    @Test
    fun `letting go after a press finishes the seek where the press got to`() {
        seek.pressed(to = 60_000f)

        assertThat(seek.released()).isEqualTo(60_000L)
        assertThat(seek.position).isNull()
    }

    @Test
    fun `the key-up of the press that brought the viewer back doesn't finish the seek`() {
        seek.pressed(to = 60_000f)
        seek.left()
        seek.reached()

        assertThat(seek.released()).isNull()
        assertThat(seek.position).isEqualTo(60_000f)
    }

    @Test
    fun `a key let go after leaving the bar doesn't finish the seek`() {
        seek.pressed(to = 60_000f)
        seek.left()

        assertThat(seek.released()).isNull()
        assertThat(seek.position).isEqualTo(60_000f)
    }

    @Test
    fun `a press after coming back carries on and finishes from there`() {
        seek.pressed(to = 60_000f)
        seek.left()
        seek.reached()

        seek.pressed(to = 96_000f)

        assertThat(seek.released()).isEqualTo(96_000L)
    }

    @Test
    fun `leaving gives a seek its time once, and leaving with none doesn't count`() {
        val before = seek.activity
        seek.left()
        assertThat(seek.activity).isEqualTo(before)

        seek.pressed(to = 60_000f)
        val pressed = seek.activity
        seek.left()
        val left = seek.activity
        assertThat(left).isNotEqualTo(pressed)

        // Focus going, then the controls hiding, mustn't stretch the time away from the bar.
        seek.left()
        assertThat(seek.activity).isEqualTo(left)
    }

    @Test
    fun `coming back gives a seek more time`() {
        seek.pressed(to = 60_000f)
        seek.left()
        val left = seek.activity

        seek.reached()

        assertThat(seek.activity).isNotEqualTo(left)
    }

    @Test
    fun `a key held at the end of the bar still counts, though the seek can't move`() {
        seek.pressed(to = 60_000f)
        val first = seek.activity

        seek.pressed(to = 60_000f)

        assertThat(seek.activity).isNotEqualTo(first)
    }

    @Test
    fun `a dropped seek is gone, and a key let go afterwards seeks nowhere`() {
        seek.pressed(to = 60_000f)

        seek.drop()

        assertThat(seek.position).isNull()
        assertThat(seek.released()).isNull()
    }
}
