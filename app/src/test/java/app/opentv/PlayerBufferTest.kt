/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv

import androidx.media3.common.util.UnstableApi
import app.opentv.player.BufferPlan
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import org.junit.Test

/** The player buffers no more than the app's memory can hold (#182). */
@UnstableApi
class PlayerBufferTest {

    @Test
    fun `a Fire TV Stick's 128 MB keeps the buffer to a quarter of it`() {
        assertThat(BufferPlan.bufferBytesFor(heapLimitBytes = 128L * MB)).isEqualTo(32 * MB)
    }

    @Test
    fun `more memory allows a bigger buffer`() {
        assertThat(BufferPlan.bufferBytesFor(heapLimitBytes = 256L * MB)).isEqualTo(64 * MB)
    }

    @Test
    fun `plenty of memory never buffers more than the player would on its own`() {
        // Media3's own limit for a stream with video and sound: 131,072,000 + 13,107,200 bytes.
        assertThat(BufferPlan.bufferBytesFor(heapLimitBytes = 2048L * MB)).isEqualTo(144_179_200)
    }

    @Test
    fun `every kind of player keeps to the memory limit`() {
        val plans = mapOf(
            "full-screen" to BufferPlan.forPlayer(heapLimitBytes = 128L * MB),
            "preview" to BufferPlan.forPlayer(preview = true, heapLimitBytes = 128L * MB),
            "live pause" to BufferPlan.forPlayer(dvr = true, heapLimitBytes = 128L * MB),
            "recording" to BufferPlan.forPlayer(liveRecording = true, heapLimitBytes = 128L * MB),
        )

        plans.forEach { (player, plan) ->
            assertWithMessage(player).that(plan.targetBytes).isEqualTo(32 * MB)
        }
    }

    @Test
    fun `only live pause keeps what's been watched to rewind into`() {
        assertThat(BufferPlan.forPlayer(heapLimitBytes = 128L * MB).backBufferMillis).isEqualTo(0)
        assertThat(BufferPlan.forPlayer(preview = true, heapLimitBytes = 128L * MB).backBufferMillis).isEqualTo(0)
    }

    @Test
    fun `on a Fire TV Stick live pause keeps half its memory to rewind into, about 28 seconds of a typical channel`() {
        // 16 MB, half of the 32 MB limit, at a typical 600 kB/s (4.8 Mbps).
        assertThat(BufferPlan.forPlayer(dvr = true, heapLimitBytes = 128L * MB).backBufferMillis).isEqualTo(27_962)
        assertThat(BufferPlan.livePauseMillis(heapLimitBytes = 128L * MB)).isEqualTo(27_962)
    }

    @Test
    fun `with plenty of memory live pause keeps its full two minutes`() {
        assertThat(BufferPlan.forPlayer(dvr = true, heapLimitBytes = 2048L * MB).backBufferMillis).isEqualTo(120_000)
        assertThat(BufferPlan.livePauseMillis(heapLimitBytes = 2048L * MB)).isEqualTo(120_000)
    }

    private companion object {
        const val MB = 1024 * 1024
    }
}
