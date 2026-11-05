/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv

import androidx.media3.common.C
import androidx.media3.common.Timeline
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.LoadControl
import androidx.media3.exoplayer.analytics.PlayerId
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.upstream.Allocator
import androidx.media3.exoplayer.upstream.DefaultAllocator
import app.opentv.player.BufferPlan
import app.opentv.player.BufferWatch
import com.google.common.truth.Truth.assertThat
import org.junit.Test

/** Live pause learns that the buffer is full from the player itself, not from a clock (#182). */
@UnstableApi
class BufferWatchTest {

    @Test
    fun `the buffer is full once the player won't load any more`() {
        val watch = BufferWatch(Loading(false))

        assertThat(watch.shouldContinueLoading(PARAMETERS)).isFalse()
        assertThat(watch.full.value).isTrue()
    }

    @Test
    fun `the buffer isn't full while the player keeps loading`() {
        val watch = BufferWatch(Loading(true))

        assertThat(watch.shouldContinueLoading(PARAMETERS)).isTrue()
        assertThat(watch.full.value).isFalse()
    }

    @Test
    fun `watching the buffer keeps live pause's rewind window and memory limit`() {
        val plan = BufferPlan.forPlayer(dvr = true, heapLimitBytes = 128L * 1024 * 1024)
        val watch = BufferWatch(plan.loadControl())

        assertThat(watch.getBackBufferDurationUs(PlayerId.UNSET)).isEqualTo(27_962_000)
        assertThat(watch.retainBackBufferFromKeyframe(PlayerId.UNSET)).isTrue()
    }

    /** A load control that always gives the same answer. */
    private class Loading(private val answer: Boolean) : LoadControl {
        override fun getAllocator(): Allocator = DefaultAllocator(true, C.DEFAULT_BUFFER_SEGMENT_SIZE)

        override fun shouldContinueLoading(parameters: LoadControl.Parameters) = answer
    }

    private companion object {
        val PARAMETERS = LoadControl.Parameters(
            PlayerId.UNSET,
            Timeline.EMPTY,
            MediaSource.MediaPeriodId(Any()),
            0,
            200 * C.MICROS_PER_SECOND,
            1f,
            false,
            false,
            C.TIME_UNSET,
        )
    }
}
