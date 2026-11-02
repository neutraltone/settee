/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv

import app.opentv.ui.LivePreviewGate
import com.google.common.truth.Truth.assertThat
import org.junit.Test

/** Home's preview stays stopped from the moment a card is picked until Home comes back (#107). */
class LivePreviewGateTest {

    private val gate = LivePreviewGate()

    @Test
    fun `the preview plays while Home is in front with nothing recording`() {
        assertThat(gate.allows(resumed = true, recording = false)).isTrue()
    }

    @Test
    fun `the preview doesn't play behind another screen or during a recording`() {
        assertThat(gate.allows(resumed = false, recording = false)).isFalse()
        assertThat(gate.allows(resumed = true, recording = true)).isFalse()
    }

    @Test
    fun `after a card is picked, a change on Home doesn't restart the preview on the way out`() {
        gate.leave()

        assertThat(gate.allows(resumed = true, recording = false)).isFalse()
    }

    @Test
    fun `coming back to Home after leaving lets the preview play again`() {
        gate.leave()
        gate.allows(resumed = false, recording = false)

        assertThat(gate.allows(resumed = true, recording = false)).isTrue()
    }
}
