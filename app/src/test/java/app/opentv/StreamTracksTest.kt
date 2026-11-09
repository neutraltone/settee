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
import androidx.media3.test.utils.ExoPlayerTestRunner
import androidx.test.core.app.ApplicationProvider
import app.opentv.data.repo.MediaKey
import app.opentv.player.DecoderSize
import app.opentv.player.PlayerController
import com.google.common.truth.Truth.assertThat
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLog

/**
 * What a stream offers, once it's known: the viewer is told when none of its sound can play here, and
 * every track goes in the log, for the next device check not to have to guess (#184).
 */
@UnstableApi
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class StreamTracksTest {

    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val players = TestPlayers(context)
    private val surface = Surface(SurfaceTexture(0))

    private val eac3 = Format.Builder()
        .setSampleMimeType(MimeTypes.AUDIO_E_AC3)
        .setChannelCount(6)
        .setSampleRate(48_000)
        .build()

    /** The stream's video has a decoder here; nothing plays its Dolby. */
    @Before
    fun videoDecoderOnly() {
        forgetVideoDecoders()
        listVideoDecoder("video.decoder", MimeTypes.VIDEO_H264, 1920, 1080)
        ShadowLog.clear()
    }

    @After
    fun close() {
        players.close()
        surface.release()
        forgetVideoDecoders()
    }

    private fun start(controller: PlayerController, channel: Long = 1) {
        controller.play(PlayerController.Request(MediaKey.Live(channel)), debounce = false)
        players.until {
            controller.player.playbackState == Player.STATE_READY && controller.tracks.value.groups.isNotEmpty()
        }
    }

    @Test
    fun `only unplayable audio keeps the picture and says why there's no sound`() {
        players.formats = listOf(ExoPlayerTestRunner.VIDEO_FORMAT, eac3)
        val controller = players.realDecoderController(surface = surface)

        start(controller)

        assertThat(controller.state.value).isInstanceOf(PlayerController.State.Playing::class.java)
        assertThat(controller.notice.value).isEqualTo(
            "This device can't play this audio format (E-AC-3). If your TV or soundbar supports it, " +
                "turning on surround sound in the device's settings may help.",
        )
    }

    @Test
    fun `it says so once per stream, and the message goes after about 5 seconds`() {
        players.formats = listOf(ExoPlayerTestRunner.VIDEO_FORMAT, eac3)
        val controller = players.realDecoderController(surface = surface)
        start(controller)

        players.advance(5_100)
        assertThat(controller.notice.value).isNull()

        controller.retry()
        players.until { controller.player.playbackState == Player.STATE_READY }
        assertThat(controller.notice.value).isNull()

        start(controller, 2)
        assertThat(controller.notice.value).isNotNull()
    }

    @Test
    fun `a muted preview says nothing`() {
        players.formats = listOf(ExoPlayerTestRunner.VIDEO_FORMAT, eac3)
        val controller = players.realDecoderController(preview = true, surface = surface)

        start(controller)

        assertThat(controller.notice.value).isNull()
    }

    @Test
    fun `sound turned off says nothing`() {
        players.formats = listOf(ExoPlayerTestRunner.VIDEO_FORMAT, eac3)
        val controller = players.realDecoderController(surface = surface)
        controller.audioEnabled = false

        start(controller)

        assertThat(controller.notice.value).isNull()
    }

    @Test
    fun `a stream with no audio tracks says nothing`() {
        players.formats = listOf(ExoPlayerTestRunner.VIDEO_FORMAT)
        val controller = players.realDecoderController(surface = surface)

        start(controller)

        assertThat(controller.notice.value).isNull()
    }

    @Test
    fun `a stream with audio this device can play says nothing`() {
        players.formats = PICTURE_AND_SOUND
        val controller = players.controller()

        start(controller)

        assertThat(controller.notice.value).isNull()
    }

    @Test
    fun `every track is logged once by named fields, selected or not, whether it's supported or not`() {
        players.formats = listOf(
            ExoPlayerTestRunner.VIDEO_FORMAT,
            eac3,
            eac3.buildUpon().setLanguage("fr").build(),
        )
        val controller = players.realDecoderController(surface = surface)

        start(controller)
        controller.disableText()
        players.until { controller.player.playbackState == Player.STATE_READY }

        val logged = trackLines()
        assertThat(logged.filter { "video/avc" in it }).hasSize(1)
        assertThat(logged.single { "video/avc" in it }).contains("size=1280x720")
        assertThat(logged.single { "video/avc" in it }).contains("selected=true supported=true")
        assertThat(logged.filter { MimeTypes.AUDIO_E_AC3 in it }).isNotEmpty()
        logged.filter { MimeTypes.AUDIO_E_AC3 in it }.forEach {
            assertThat(it).contains("channels=6 sampleRate=48000")
            assertThat(it).contains("selected=false supported=false")
        }
    }

    @Test
    fun `a track refused for its size is logged as unsupported`() {
        players.formats = listOf(
            Format.Builder().setSampleMimeType(MimeTypes.VIDEO_H264).setWidth(3840).setHeight(2160).build(),
        )
        players.decoders = FakeDecoders(MimeTypes.VIDEO_H264 to DecoderSize(1920, 1088))
        val controller = players.controller()

        controller.play(PlayerController.Request(MediaKey.Live(1)), debounce = false)
        players.until { controller.state.value is PlayerController.State.Error }

        assertThat(trackLines().single()).contains("size=3840x2160")
        assertThat(trackLines().single()).contains("supported=false")
    }

    @Test
    fun `a stream's address in a track's id or label stays out of the log`() {
        players.formats = listOf(
            ExoPlayerTestRunner.VIDEO_FORMAT.buildUpon().setId("https://example.com/live/u/p/1.ts").build(),
            eac3.buildUpon().setLabel("https://example.com/live/u/p/1.ts").build(),
        )
        val controller = players.realDecoderController(surface = surface)

        start(controller)

        assertThat(trackLines()).isNotEmpty()
        assertThat(ShadowLog.getLogsForTag("OpenTV").map { it.msg }.filter { "example.com" in it }).isEmpty()
    }

    private fun trackLines() = ShadowLog.getLogsForTag("OpenTV").map { it.msg }.filter { it.startsWith("Track:") }
}
