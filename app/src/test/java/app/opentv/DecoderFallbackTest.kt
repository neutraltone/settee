/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv

import android.content.Context
import android.graphics.SurfaceTexture
import android.media.MediaCodec
import android.view.Surface
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.analytics.AnalyticsListener
import androidx.test.core.app.ApplicationProvider
import app.opentv.data.repo.MediaKey
import app.opentv.player.PlayerController
import com.google.common.truth.Correspondence
import com.google.common.truth.Truth.assertThat
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.shadows.ShadowLog
import org.robolectric.shadows.ShadowMediaCodec

/**
 * A device can list a decoder that then won't start. The full-screen players try the next one rather
 * than give up (#107). The first decoder listed is the first tried, and [FirstDecoderWontStart] fails it.
 */
@UnstableApi
@RunWith(RobolectricTestRunner::class)
@Config(
    sdk = [34],
    application = android.app.Application::class,
    shadows = [DecoderFallbackTest.FirstDecoderWontStart::class],
)
class DecoderFallbackTest {

    /** Robolectric starts any decoder by any name, so the failure has to be made here. */
    @Implements(MediaCodec::class)
    class FirstDecoderWontStart : ShadowMediaCodec() {
        @Implementation
        override fun native_start() {
            if (startsToFail-- > 0) error("Decoder won't start")
            super.native_start()
        }

        companion object {
            var startsToFail = 0
        }
    }

    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val players = TestPlayers(context)
    private val surface = Surface(SurfaceTexture(0))

    /** The decoders that really started, in order. */
    private val started = mutableListOf<String>()

    @Before
    fun decoders() {
        FirstDecoderWontStart.startsToFail = 1
        forgetVideoDecoders()
        listOf(FIRST, SECOND).forEach { listVideoDecoder(it, MimeTypes.VIDEO_H264, 1920, 1080) }
    }

    @After
    fun close() {
        players.close()
        surface.release()
        forgetVideoDecoders()
    }

    @Test
    fun `full-screen playback moves on to the next decoder when the first won't start`() {
        val controller = players.realDecoderController(surface = surface)

        val state = playUntilSettled(controller)

        assertThat(state).isInstanceOf(PlayerController.State.Playing::class.java)
        assertThat(started).containsExactly(SECOND)
        assertThat(logged()).comparingElementsUsing(CONTAINS).containsAtLeast(FIRST, SECOND)
    }

    @Test
    fun `the preview doesn't try other decoders, and names what it couldn't play`() {
        val controller = players.realDecoderController(preview = true, surface = surface)

        val state = playUntilSettled(controller)

        assertThat(controller.player.playerError?.errorCode)
            .isEqualTo(PlaybackException.ERROR_CODE_DECODER_INIT_FAILED)
        assertThat((state as PlayerController.State.Error).reason.sentence)
            .isEqualTo("This device can't play this video format.")
        assertThat(state.reason.detail).isEqualTo("Decoder init failed · H.264, 1280x720")
        assertThat(logged()).comparingElementsUsing(CONTAINS).contains(FIRST)
        assertThat(started).isEmpty()
    }

    @Test
    fun `a format the device can't play isn't restarted, since trying again can't help`() {
        val controller = players.realDecoderController(preview = true, surface = surface)

        val state = playUntilSettled(controller)
        players.advance(10_000)

        assertThat((state as PlayerController.State.Error).canRetry).isFalse()
        assertThat(players.played).hasSize(1)
        assertThat(controller.state.value).isInstanceOf(PlayerController.State.Error::class.java)
    }

    private fun logged() = ShadowLog.getLogsForTag("OpenTV").map { it.msg }

    private fun playUntilSettled(controller: PlayerController): PlayerController.State {
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
        controller.play(PlayerController.Request(MediaKey.Movie(1)), debounce = false)
        players.until {
            controller.player.playbackState == Player.STATE_READY || controller.player.playerError != null
        }
        return controller.state.value
    }

    private companion object {
        const val FIRST = "first.decoder"
        const val SECOND = "second.decoder"
        val CONTAINS: Correspondence<String, String> = Correspondence.from(
            { actual, expected -> actual?.contains(expected!!) == true },
            "contains",
        )
    }
}
