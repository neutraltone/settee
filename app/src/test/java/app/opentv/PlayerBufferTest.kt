/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv

import androidx.media3.common.util.UnstableApi
import app.opentv.player.PlayerController
import com.google.common.truth.Truth.assertThat
import org.junit.Test

/** The player buffers no more than the app's memory can hold (#182). */
@UnstableApi
class PlayerBufferTest {

    @Test
    fun `a Fire TV Stick's 128 MB keeps the buffer to a quarter of it`() {
        assertThat(PlayerController.bufferBytesFor(heapLimitBytes = 128L * MB)).isEqualTo(32 * MB)
    }

    @Test
    fun `more memory allows a bigger buffer`() {
        assertThat(PlayerController.bufferBytesFor(heapLimitBytes = 256L * MB)).isEqualTo(64 * MB)
    }

    @Test
    fun `plenty of memory never buffers more than the player would on its own`() {
        // Media3's own limit for a stream with video and sound: 131,072,000 + 13,107,200 bytes.
        assertThat(PlayerController.bufferBytesFor(heapLimitBytes = 2048L * MB)).isEqualTo(144_179_200)
    }

    private companion object {
        const val MB = 1024 * 1024
    }
}
