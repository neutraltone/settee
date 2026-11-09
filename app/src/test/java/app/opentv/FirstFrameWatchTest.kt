/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv

import android.content.Context
import android.graphics.SurfaceTexture
import android.view.Surface
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.Player
import androidx.media3.common.Timeline
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.analytics.AnalyticsListener
import androidx.test.core.app.ApplicationProvider
import app.opentv.data.repo.MediaKey
import app.opentv.player.FirstFrameWatch
import app.opentv.player.PlayerController
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * What starts and ends the first-frame limit (#184), which a fake decoder can't show: real Media3 reports
 * a format with no surface and no decoder, and events from the last stream can land after the next starts.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@UnstableApi
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class FirstFrameWatchTest {

    private val scope = TestScope()
    private val late = mutableListOf<Format?>()
    private val watch = FirstFrameWatch(scope) { late += it }
    private val hevc = Format.Builder().setSampleMimeType(MimeTypes.VIDEO_H265).build()

    private val event = AnalyticsListener.EventTime(
        0L,
        Timeline.EMPTY,
        0,
        null,
        0L,
        Timeline.EMPTY,
        0,
        null,
        0L,
        0L,
    )

    @After
    fun forget() = forgetVideoDecoders()

    private fun decoderStarted() = watch.onVideoDecoderInitialized(event, "decoder", 0L, 0L)

    private fun formatArrived() = watch.onVideoInputFormatChanged(event, hevc, null)

    private fun frameShown() = watch.onRenderedFirstFrame(event, Any(), 0L)

    private fun afterTheLimit() {
        scope.advanceTimeBy(LIMIT_MILLIS + 1)
        scope.runCurrent()
    }

    @Test
    fun `a format with no decoder started doesn't start the clock`() {
        formatArrived()

        afterTheLimit()

        assertThat(late).isEmpty()
    }

    @Test
    fun `a decoder starting does, and the limit names the format it was given`() {
        formatArrived()
        decoderStarted()

        afterTheLimit()

        assertThat(late).containsExactly(hevc)
    }

    @Test
    fun `a frame from the last stream, landing late, doesn't end the next stream's wait`() {
        decoderStarted()
        watch.newStream()
        frameShown()
        decoderStarted()

        afterTheLimit()

        assertThat(late).hasSize(1)
    }

    @Test
    fun `this stream's frame ends the wait`() {
        decoderStarted()
        frameShown()

        afterTheLimit()

        assertThat(late).isEmpty()
    }

    @Test
    fun `changing stream on real decoders starts a decoder again, which is what starts the clock`() {
        forgetVideoDecoders()
        listVideoDecoder("video.decoder", MimeTypes.VIDEO_H264, 1920, 1080)
        val surface = Surface(SurfaceTexture(0))
        val players = TestPlayers(ApplicationProvider.getApplicationContext<Context>())
        val started = mutableListOf<String>()
        val controller = players.realDecoderController(surface = surface)
        controller.player.addAnalyticsListener(object : AnalyticsListener {
            override fun onVideoDecoderInitialized(
                eventTime: AnalyticsListener.EventTime,
                decoderName: String,
                initializedTimestampMs: Long,
                initializationDurationMs: Long,
            ) {
                started += decoderName
            }
        })

        controller.play(PlayerController.Request(MediaKey.Live(1)), debounce = false)
        players.until { controller.player.playbackState == Player.STATE_READY }
        controller.play(PlayerController.Request(MediaKey.Live(2)), debounce = false)
        players.until { started.size == 2 }

        assertThat(started).hasSize(2)
        players.close()
        surface.release()
    }

    private companion object {
        const val LIMIT_MILLIS = 15_000L
    }
}
