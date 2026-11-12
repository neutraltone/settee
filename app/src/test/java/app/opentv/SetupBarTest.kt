/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv

import app.opentv.data.repo.SetupBar
import app.opentv.data.repo.SetupStep
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class SetupBarTest {

    @Test
    fun `a full setup shares the bar as the steps took on a Fire TV Stick`() {
        val bar = SetupBar(SetupStep.entries)

        assertThat(bar.reached(SetupStep.CHANNELS, done = 1f)).isWithin(0.001f).of(0.15f)
        assertThat(bar.reached(SetupStep.GUIDE, done = 1f)).isWithin(0.001f).of(0.76f)
        assertThat(bar.reached(SetupStep.MOVIES, done = 1f)).isWithin(0.001f).of(0.92f)
    }

    @Test
    fun `the steps that run share the whole bar in the same proportions`() {
        // Live TV switched off: no guide. 15 + 16 + 8 = 39.
        val bar = SetupBar(listOf(SetupStep.CHANNELS, SetupStep.MOVIES, SetupStep.SHOWS))

        assertThat(bar.reached(SetupStep.CHANNELS, done = 1f)).isWithin(0.001f).of(15f / 39)
        assertThat(bar.reached(SetupStep.MOVIES, done = 0.5f)).isWithin(0.001f).of(23f / 39)
    }

    @Test
    fun `a playlist with only channels and a guide splits the bar 20 to 80`() {
        val bar = SetupBar(listOf(SetupStep.CHANNELS, SetupStep.GUIDE))

        assertThat(bar.reached(SetupStep.CHANNELS, done = 1f)).isWithin(0.005f).of(0.2f)
    }

    @Test
    fun `the bar never goes backwards`() {
        val bar = SetupBar(SetupStep.entries)

        val ahead = bar.advance(SetupStep.GUIDE, done = 0.5f)
        // A step reporting less than it already had, such as an estimate corrected downwards.
        val after = bar.advance(SetupStep.GUIDE, done = 0.3f)

        assertThat(after).isEqualTo(ahead)
    }

    @Test
    fun `the bar is never full before setup is done`() {
        val bar = SetupBar(SetupStep.entries)

        assertThat(bar.advance(SetupStep.SHOWS, done = 1f)).isLessThan(1f)
        assertThat(bar.finish()).isEqualTo(1f)
    }
}
