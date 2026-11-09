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
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.analytics.AnalyticsListener
import androidx.test.core.app.ApplicationProvider
import app.opentv.data.repo.MediaKey
import app.opentv.player.PlayerController
import com.google.common.truth.Truth.assertThat
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * A video bigger than every decoder for its codec lists is refused before any decoder starts, so it
 * can't reach a software decoder rated well below it and play as a slideshow (#184).
 */
@UnstableApi
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class OversizeTrackTest {

    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val players = TestPlayers(context)
    private val surface = Surface(SurfaceTexture(0))

    /** The video decoders that really started, in order. */
    private val started = mutableListOf<String>()

    @Before
    fun noDecoders() = forgetVideoDecoders()

    @After
    fun close() {
        players.close()
        surface.release()
        forgetVideoDecoders()
    }

    @Test
    fun `a track above every decoder's size starts no decoder and names the format and size`() {
        decoder("hardware.hevc", 1920, 1088)
        decoder("software.hevc", 1920, 1088)
        players.formats = listOf(hevc(3840, 1600))

        val state = playUntilSettled()

        assertThat(started).isEmpty()
        assertThat(state).isEqualTo(
            PlayerController.State.Error(
                title = "Film",
                message = "This device can't play this video (HEVC, 3840x1600).",
                canRetry = false,
            ),
        )
    }

    @Test
    fun `a refused track isn't restarted, since trying again can't help`() {
        decoder("hardware.hevc", 1920, 1088)
        players.formats = listOf(hevc(3840, 2160))

        val state = playUntilSettled()
        players.advance(10_000)

        assertThat(players.played).hasSize(1)
        assertThat((state as PlayerController.State.Error).canRetry).isFalse()
    }

    @Test
    fun `a track within a decoder's size plays`() {
        decoder("hardware.hevc", 1920, 1088)
        players.formats = listOf(hevc(1920, 1080))

        val state = playUntilSettled()

        assertThat(state).isInstanceOf(PlayerController.State.Playing::class.java)
        assertThat(started).containsExactly("hardware.hevc")
    }

    @Test
    fun `one decoder that lists the size is enough`() {
        decoder("small.hevc", 1920, 1088)
        decoder("big.hevc", 3840, 2160)
        players.formats = listOf(hevc(3840, 2160))

        assertThat(playUntilSettled()).isInstanceOf(PlayerController.State.Playing::class.java)
    }

    @Test
    fun `a decoder for another codec doesn't make a track fit`() {
        decoder("hardware.hevc", 1920, 1088)
        decoder("big.avc", 3840, 2160, MimeTypes.VIDEO_H264)
        players.formats = listOf(hevc(3840, 1600))

        assertThat((playUntilSettled() as PlayerController.State.Error).message)
            .isEqualTo("This device can't play this video (HEVC, 3840x1600).")
        assertThat(started).isEmpty()
    }

    @Test
    fun `Dolby Vision is measured against the decoders it falls back on`() {
        decoder("hardware.hevc", 1920, 1088)
        players.formats = listOf(
            Format.Builder()
                .setSampleMimeType(MimeTypes.VIDEO_DOLBY_VISION)
                .setCodecs("dvhe.08.06")
                .setWidth(3840)
                .setHeight(2160)
                .build(),
        )

        assertThat((playUntilSettled() as PlayerController.State.Error).message)
            .isEqualTo("This device can't play this video (Dolby Vision, 3840x2160).")
        assertThat(started).isEmpty()
    }

    @Test
    fun `video with no decoder here at all says so, rather than a clock counting over a blank screen`() {
        decoder("hardware.hevc", 1920, 1088)
        // Profile 5 has no HEVC base layer to fall back on, and the Dolby sound can't play either.
        players.formats = listOf(
            Format.Builder()
                .setSampleMimeType(MimeTypes.VIDEO_DOLBY_VISION)
                .setCodecs("dvhe.05.06")
                .setWidth(3840)
                .setHeight(2160)
                .build(),
            Format.Builder().setSampleMimeType(MimeTypes.AUDIO_E_AC3).setChannelCount(6).setSampleRate(48_000).build(),
        )

        assertThat((playUntilSettled() as PlayerController.State.Error).message)
            .isEqualTo("This device can't play this video (Dolby Vision, 3840x2160).")
    }

    private fun hevc(width: Int, height: Int): Format =
        Format.Builder().setSampleMimeType(MimeTypes.VIDEO_H265).setWidth(width).setHeight(height).build()

    private fun decoder(name: String, width: Int, height: Int, mimeType: String = MimeTypes.VIDEO_H265) =
        listVideoDecoder(name, mimeType, width, height)

    private fun playUntilSettled(): PlayerController.State {
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
        controller.play(PlayerController.Request(MediaKey.Movie(1)), debounce = false)
        players.until {
            controller.player.playbackState == Player.STATE_READY ||
                controller.state.value is PlayerController.State.Error
        }
        return controller.state.value
    }
}
