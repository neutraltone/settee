/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv

import android.content.Context
import androidx.media3.common.C
import androidx.media3.common.ColorInfo
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlaybackException
import androidx.test.core.app.ApplicationProvider
import app.opentv.player.PlaybackErrors
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLog

/** A decoder failure says which format this device can't play (#107). */
@UnstableApi
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class PlaybackErrorsTest {

    private val context = ApplicationProvider.getApplicationContext<Context>()

    @Test
    fun `a decoder that won't start names the 10-bit HEVC it was given`() {
        val format = Format.Builder().setSampleMimeType(MimeTypes.VIDEO_H265).setCodecs("hvc1.2.4.L153.B0").build()

        assertThat(PlaybackErrors.describe(context, decoderFailure(format)))
            .isEqualTo("This device can't play this video format (HEVC 10-bit).")
    }

    @Test
    fun `10-bit AV1 reported only by its colour info is named too`() {
        val format = Format.Builder()
            .setSampleMimeType(MimeTypes.VIDEO_AV1)
            .setColorInfo(ColorInfo.Builder().setLumaBitdepth(10).setChromaBitdepth(10).build())
            .build()

        assertThat(PlaybackErrors.describe(context, decoderFailure(format)))
            .isEqualTo("This device can't play this video format (AV1 10-bit).")
    }

    @Test
    fun `a failure of the audio decoder says audio, not video`() {
        val format = Format.Builder().setSampleMimeType(MimeTypes.AUDIO_E_AC3).build()

        assertThat(PlaybackErrors.describe(context, decoderFailure(format)))
            .isEqualTo("This device can't play this audio format (E-AC-3).")
    }

    @Test
    fun `a failure without a format still says the device can't play it`() {
        assertThat(PlaybackErrors.describe(context, decoderFailure(format = null)))
            .isEqualTo("This device can't play this format.")
    }

    @Test
    fun `a format we have no name for is shown by its own type`() {
        val format = Format.Builder().setSampleMimeType("video/x-unnamed").build()

        assertThat(PlaybackErrors.describe(context, decoderFailure(format)))
            .isEqualTo("This device can't play this video format (X-UNNAMED).")
    }

    @Test
    fun `a stream asking for more than the decoder can do names the format as well`() {
        val format = Format.Builder().setSampleMimeType(MimeTypes.VIDEO_H264).build()
        val error = decoderFailure(format, PlaybackException.ERROR_CODE_DECODING_FORMAT_EXCEEDS_CAPABILITIES)

        assertThat(PlaybackErrors.describe(context, error))
            .isEqualTo("This device can't play this video format (H.264). A different quality may work.")
    }

    @Test
    fun `an unsupported video format suggests another quality`() {
        val format = Format.Builder().setSampleMimeType(MimeTypes.VIDEO_H265).build()
        val error = decoderFailure(format, PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED)

        assertThat(PlaybackErrors.describe(context, error))
            .isEqualTo("This device can't play this video format (HEVC). A different quality may work.")
    }

    @Test
    fun `an unsupported audio format doesn't suggest another quality`() {
        val format = Format.Builder().setSampleMimeType(MimeTypes.AUDIO_DTS).build()
        val error = decoderFailure(format, PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED)

        assertThat(PlaybackErrors.describe(context, error))
            .isEqualTo("This device can't play this audio format (DTS).")
    }

    @Test
    fun `a decoder that fails after starting names the format too`() {
        val format = Format.Builder().setSampleMimeType(MimeTypes.VIDEO_H265).setCodecs("hvc1.2.4.L153.B0").build()
        val error = decoderFailure(format, PlaybackException.ERROR_CODE_DECODING_FAILED)

        assertThat(PlaybackErrors.describe(context, error))
            .isEqualTo("This device can't play this video format (HEVC 10-bit).")
    }

    @Test
    fun `10-bit H_264 is named by its High 10 profile`() {
        val format = Format.Builder().setSampleMimeType(MimeTypes.VIDEO_H264).setCodecs("avc1.6E0028").build()

        assertThat(PlaybackErrors.describe(context, decoderFailure(format)))
            .isEqualTo("This device can't play this video format (H.264 10-bit).")
    }

    @Test
    fun `an HEVC profile with a profile space prefix is still read`() {
        val format = Format.Builder().setSampleMimeType(MimeTypes.VIDEO_H265).setCodecs("hvc1.A2.4.L153.B0").build()

        assertThat(PlaybackErrors.describe(context, decoderFailure(format)))
            .isEqualTo("This device can't play this video format (HEVC 10-bit).")
    }

    @Test
    fun `12-bit is named as well`() {
        val format = Format.Builder().setSampleMimeType(MimeTypes.VIDEO_VP9).setCodecs("vp09.02.10.12").build()

        assertThat(PlaybackErrors.describe(context, decoderFailure(format)))
            .isEqualTo("This device can't play this video format (VP9 12-bit).")
    }

    @Test
    fun `the codec matching the track is read, whichever is listed first`() {
        val format = Format.Builder()
            .setSampleMimeType(MimeTypes.VIDEO_H265)
            .setCodecs("mp4a.40.2,hvc1.2.4.L153.B0")
            .build()

        assertThat(PlaybackErrors.describe(context, decoderFailure(format)))
            .isEqualTo("This device can't play this video format (HEVC 10-bit).")
    }

    @Test
    fun `common audio formats get their proper names`() {
        fun named(mimeType: String) = PlaybackErrors.describe(
            context,
            decoderFailure(Format.Builder().setSampleMimeType(mimeType).build()),
        )

        assertThat(named(MimeTypes.AUDIO_DTS_HD)).contains("(DTS-HD)")
        assertThat(named(MimeTypes.AUDIO_MPEG_L2)).contains("(MP2)")
        assertThat(named(MimeTypes.AUDIO_E_AC3_JOC)).contains("(E-AC-3 JOC)")
    }

    @Test
    fun `8-bit HEVC doesn't claim to be 10-bit`() {
        val format = Format.Builder().setSampleMimeType(MimeTypes.VIDEO_H265).setCodecs("hev1.1.6.L120.90").build()

        assertThat(PlaybackErrors.describe(context, decoderFailure(format)))
            .isEqualTo("This device can't play this video format (HEVC).")
    }

    @Test
    fun `10-bit VP9 reported by its codec string is named`() {
        val format = Format.Builder().setSampleMimeType(MimeTypes.VIDEO_VP9).setCodecs("vp09.02.10.10").build()

        assertThat(PlaybackErrors.describe(context, decoderFailure(format)))
            .isEqualTo("This device can't play this video format (VP9 10-bit).")
    }

    @Test
    fun `the failure is logged with the codec details and nothing that identifies the provider`() {
        val format = Format.Builder()
            .setId("http://example.com/u/p/1.ts")
            .setSampleMimeType(MimeTypes.VIDEO_H265)
            .setCodecs("hvc1.2.4.L153.B0")
            .setWidth(3840)
            .setHeight(2160)
            .build()
        val cause = IllegalStateException("Failed to open http://example.com/u/p/1.ts")

        PlaybackErrors.logDecoderFailure(decoderFailure(format, cause = cause))

        val logged = ShadowLog.getLogsForTag("OpenTV").single()
        assertThat(logged.msg).contains("video/hevc")
        assertThat(logged.msg).contains("hvc1.2.4.L153.B0")
        assertThat(logged.msg).contains("3840x2160")
        assertThat(logged.throwable).isNull()
        assertThat(logged.msg).doesNotContain("example.com")
    }

    @Test
    fun `an audio failure logs channels and sample rate, not a picture size`() {
        val format = Format.Builder()
            .setSampleMimeType(MimeTypes.AUDIO_E_AC3)
            .setChannelCount(6)
            .setSampleRate(48_000)
            .build()

        PlaybackErrors.logDecoderFailure(decoderFailure(format))

        val logged = ShadowLog.getLogsForTag("OpenTV").single().msg
        assertThat(logged).contains("channels=6")
        assertThat(logged).contains("sampleRate=48000")
        assertThat(logged).doesNotContain("size=")
    }

    @Test
    fun `a failure that isn't the decoder's logs nothing`() {
        val error = PlaybackException("gone", null, PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED)

        PlaybackErrors.logDecoderFailure(error)

        assertThat(ShadowLog.getLogsForTag("OpenTV")).isEmpty()
    }

    private fun decoderFailure(
        format: Format?,
        errorCode: Int = PlaybackException.ERROR_CODE_DECODER_INIT_FAILED,
        cause: Exception = IllegalStateException("decoder"),
    ) = ExoPlaybackException.createForRenderer(
        cause,
        "MediaCodecVideoRenderer",
        0,
        format,
        C.FORMAT_HANDLED,
        false,
        errorCode,
    )
}
