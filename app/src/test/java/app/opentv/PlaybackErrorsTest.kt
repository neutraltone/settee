/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv

import android.content.Context
import android.net.Uri
import androidx.media3.common.C
import androidx.media3.common.ColorInfo
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Timeline
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.HttpDataSource
import androidx.media3.exoplayer.ExoPlaybackException
import androidx.media3.exoplayer.analytics.AnalyticsListener
import androidx.media3.exoplayer.upstream.Loader
import androidx.test.core.app.ApplicationProvider
import app.opentv.player.DecoderLog
import app.opentv.player.PlaybackErrors
import app.opentv.player.readableCodeName
import com.google.common.truth.Truth.assertThat
import java.net.SocketTimeoutException
import java.net.UnknownHostException
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
    fun `a decoder that won't start names the 10-bit HEVC it was given on the detail line`() {
        val format = Format.Builder().setSampleMimeType(MimeTypes.VIDEO_H265).setCodecs("hvc1.2.4.L153.B0").build()

        val shown = PlaybackErrors.describe(context, decoderFailure(format), onTelevision = false)
        assertThat(shown.sentence).isEqualTo("This device can't play this video format.")
        assertThat(shown.detail).endsWith(" · HEVC 10-bit")
    }

    @Test
    fun `10-bit AV1 reported only by its colour info is named too`() {
        val format = Format.Builder()
            .setSampleMimeType(MimeTypes.VIDEO_AV1)
            .setColorInfo(ColorInfo.Builder().setLumaBitdepth(10).setChromaBitdepth(10).build())
            .build()

        val shown = PlaybackErrors.describe(context, decoderFailure(format), onTelevision = false)
        assertThat(shown.sentence).isEqualTo("This device can't play this video format.")
        assertThat(shown.detail).endsWith(" · AV1 10-bit")
    }

    @Test
    fun `a failure of the audio decoder says audio, not video`() {
        val format = Format.Builder().setSampleMimeType(MimeTypes.AUDIO_E_AC3).build()

        val shown = PlaybackErrors.describe(context, decoderFailure(format), onTelevision = false)
        assertThat(shown.sentence).isEqualTo("This device can't play this audio format.")
        assertThat(shown.detail).endsWith(" · E-AC-3")
    }

    @Test
    fun `a failure without a format still says the device can't play it`() {
        val shown = PlaybackErrors.describe(context, decoderFailure(format = null), onTelevision = true)
        assertThat(shown.sentence).isEqualTo("This TV can't play this format.")
        assertThat(shown.detail).isEqualTo("Decoder init failed")
    }

    @Test
    fun `a format we have no name for is shown by its own type`() {
        val format = Format.Builder().setSampleMimeType("video/x-unnamed").build()

        val shown = PlaybackErrors.describe(context, decoderFailure(format), onTelevision = false)
        assertThat(shown.sentence).isEqualTo("This device can't play this video format.")
        assertThat(shown.detail).endsWith(" · X-UNNAMED")
    }

    @Test
    fun `a stream asking for more than the decoder can do names the format as well`() {
        val format = Format.Builder().setSampleMimeType(MimeTypes.VIDEO_H264).build()
        val error = decoderFailure(format, PlaybackException.ERROR_CODE_DECODING_FORMAT_EXCEEDS_CAPABILITIES)

        val shown = PlaybackErrors.describe(context, error, onTelevision = false)
        assertThat(shown.sentence).isEqualTo("This device can't play this video format. A different quality may work.")
        assertThat(shown.detail).endsWith(" · H.264")
    }

    @Test
    fun `an unsupported video format suggests another quality`() {
        val format = Format.Builder().setSampleMimeType(MimeTypes.VIDEO_H265).build()
        val error = decoderFailure(format, PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED)

        val shown = PlaybackErrors.describe(context, error, onTelevision = false)
        assertThat(shown.sentence).isEqualTo("This device can't play this video format. A different quality may work.")
        assertThat(shown.detail).endsWith(" · HEVC")
    }

    @Test
    fun `an unsupported audio format doesn't suggest another quality`() {
        val format = Format.Builder().setSampleMimeType(MimeTypes.AUDIO_DTS).build()
        val error = decoderFailure(format, PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED)

        val shown = PlaybackErrors.describe(context, error, onTelevision = false)
        assertThat(shown.sentence).isEqualTo("This device can't play this audio format.")
        assertThat(shown.detail).endsWith(" · DTS")
    }

    @Test
    fun `a decoder that fails after starting names the format too`() {
        val format = Format.Builder().setSampleMimeType(MimeTypes.VIDEO_H265).setCodecs("hvc1.2.4.L153.B0").build()
        val error = decoderFailure(format, PlaybackException.ERROR_CODE_DECODING_FAILED)

        val shown = PlaybackErrors.describe(context, error, onTelevision = false)
        assertThat(shown.sentence).isEqualTo("This device can't play this video format.")
        assertThat(shown.detail).endsWith(" · HEVC 10-bit")
    }

    @Test
    fun `10-bit H_264 is named by its High 10 profile`() {
        val format = Format.Builder().setSampleMimeType(MimeTypes.VIDEO_H264).setCodecs("avc1.6E0028").build()

        val shown = PlaybackErrors.describe(context, decoderFailure(format), onTelevision = false)
        assertThat(shown.sentence).isEqualTo("This device can't play this video format.")
        assertThat(shown.detail).endsWith(" · H.264 10-bit")
    }

    @Test
    fun `an HEVC profile with a profile space prefix is still read`() {
        val format = Format.Builder().setSampleMimeType(MimeTypes.VIDEO_H265).setCodecs("hvc1.A2.4.L153.B0").build()

        val shown = PlaybackErrors.describe(context, decoderFailure(format), onTelevision = false)
        assertThat(shown.sentence).isEqualTo("This device can't play this video format.")
        assertThat(shown.detail).endsWith(" · HEVC 10-bit")
    }

    @Test
    fun `12-bit is named as well`() {
        val format = Format.Builder().setSampleMimeType(MimeTypes.VIDEO_VP9).setCodecs("vp09.02.10.12").build()

        val shown = PlaybackErrors.describe(context, decoderFailure(format), onTelevision = false)
        assertThat(shown.sentence).isEqualTo("This device can't play this video format.")
        assertThat(shown.detail).endsWith(" · VP9 12-bit")
    }

    @Test
    fun `the codec matching the track is read, whichever is listed first`() {
        val format = Format.Builder()
            .setSampleMimeType(MimeTypes.VIDEO_H265)
            .setCodecs("mp4a.40.2,hvc1.2.4.L153.B0")
            .build()

        val shown = PlaybackErrors.describe(context, decoderFailure(format), onTelevision = false)
        assertThat(shown.sentence).isEqualTo("This device can't play this video format.")
        assertThat(shown.detail).endsWith(" · HEVC 10-bit")
    }

    @Test
    fun `common audio formats get their proper names`() {
        fun named(mimeType: String) = PlaybackErrors.describe(
            context,
            decoderFailure(Format.Builder().setSampleMimeType(mimeType).build()),
            onTelevision = false,
        ).detail

        assertThat(named(MimeTypes.AUDIO_DTS_HD)).endsWith(" · DTS-HD")
        assertThat(named(MimeTypes.AUDIO_MPEG_L2)).endsWith(" · MP2")
        assertThat(named(MimeTypes.AUDIO_E_AC3_JOC)).endsWith(" · E-AC-3 JOC")
    }

    @Test
    fun `8-bit HEVC doesn't claim to be 10-bit`() {
        val format = Format.Builder().setSampleMimeType(MimeTypes.VIDEO_H265).setCodecs("hev1.1.6.L120.90").build()

        val shown = PlaybackErrors.describe(context, decoderFailure(format), onTelevision = false)
        assertThat(shown.sentence).isEqualTo("This device can't play this video format.")
        assertThat(shown.detail).endsWith(" · HEVC")
    }

    @Test
    fun `10-bit VP9 reported by its codec string is named`() {
        val format = Format.Builder().setSampleMimeType(MimeTypes.VIDEO_VP9).setCodecs("vp09.02.10.10").build()

        val shown = PlaybackErrors.describe(context, decoderFailure(format), onTelevision = false)
        assertThat(shown.sentence).isEqualTo("This device can't play this video format.")
        assertThat(shown.detail).endsWith(" · VP9 10-bit")
    }

    @Test
    fun `running out of memory while loading says the stream is too big for this device, not an IO code`() {
        // How Media3 reports it: the loader wraps the error, and the player calls it a source error.
        val error = ExoPlaybackException.createForSource(
            Loader.UnexpectedLoaderException(OutOfMemoryError("Failed to allocate")),
            PlaybackException.ERROR_CODE_IO_UNSPECIFIED,
        )

        val shown = PlaybackErrors.describe(context, error, onTelevision = true)
        assertThat(
            shown.sentence,
        ).isEqualTo("This stream needs more memory than this TV has. A different quality may work.")
        assertThat(shown.detail).isEqualTo("IO unspecified")
    }

    @Test
    fun `a login the provider won't accept says so, with the status on the detail line`() {
        val shown = PlaybackErrors.describe(context, httpFailure(401), onTelevision = true)

        assertThat(shown.sentence)
            .isEqualTo("Your provider didn't accept your login. Check the username and password in Settings.")
        assertThat(shown.detail).isEqualTo("IO bad HTTP status · HTTP 401")
    }

    @Test
    fun `each HTTP status says whose end it's on and what to do, and never the code`() {
        val expected = mapOf(
            403 to
                "Your provider refused this. Your account may be in use on another device, " +
                "so stop it there and try again.",
            404 to "This isn't on your provider any more. Try refreshing your provider in Settings.",
            405 to "This didn't play. Try again, or pick something else.",
            429 to "Your provider is getting too many requests from this device. Wait a minute, then try again.",
            451 to "Your provider can't show this where you are.",
            500 to "Your provider is having problems. That's at their end, so try again later.",
            503 to "Your provider is having problems. That's at their end, so try again later.",
            418 to "This didn't play. Try again, or pick something else.",
        )

        expected.forEach { (status, sentence) ->
            val shown = PlaybackErrors.describe(context, httpFailure(status), onTelevision = false)
            assertThat(shown.sentence).isEqualTo(sentence)
            assertThat(shown.detail).isEqualTo("IO bad HTTP status · HTTP $status")
        }
    }

    @Test
    fun `a provider that can't be reached says this TV on a television`() {
        val shown = PlaybackErrors.describe(context, unreachable(), onTelevision = true)

        assertThat(
            shown.sentence,
        ).isEqualTo("Can't reach your provider. Check this TV is online and the address is right.")
        assertThat(shown.detail).isEqualTo("IO network connection failed")
    }

    @Test
    fun `a provider that can't be reached says this device on a phone`() {
        val shown = PlaybackErrors.describe(context, unreachable(), onTelevision = false)

        assertThat(shown.sentence)
            .isEqualTo("Can't reach your provider. Check this device is online and the address is right.")
    }

    @Test
    fun `a provider that stops responding says it may be busy`() {
        val error = ExoPlaybackException.createForSource(
            SocketTimeoutException("timeout reading http://example.com/u/p/1.ts"),
            PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT,
        )

        val shown = PlaybackErrors.describe(context, error, onTelevision = true)

        assertThat(
            shown.sentence,
        ).isEqualTo("Your provider stopped responding. It may be busy, so try again in a moment.")
        assertThat(shown.detail).isEqualTo("IO network connection timeout")
    }

    @Test
    fun `the other playback failures follow Voice, with Media3's name for them on the detail line`() {
        val expected = mapOf(
            PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED to
                "Can't reach your provider. Check this TV is online and the address is right.",
            PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT to
                "Your provider stopped responding. It may be busy, so try again in a moment.",
            PlaybackException.ERROR_CODE_IO_INVALID_HTTP_CONTENT_TYPE to
                "Your provider sent a web page instead of video. The link may have expired, " +
                "so try refreshing your provider in Settings.",
            PlaybackException.ERROR_CODE_PARSING_CONTAINER_MALFORMED to
                "This video is damaged and won't play. That's at your provider's end, so try something else.",
            PlaybackException.ERROR_CODE_PARSING_MANIFEST_MALFORMED to
                "This video is damaged and won't play. That's at your provider's end, so try something else.",
            PlaybackException.ERROR_CODE_BEHIND_LIVE_WINDOW to
                "This channel fell too far behind live. Try again to catch up.",
            PlaybackException.ERROR_CODE_REMOTE_ERROR to "This didn't play. Try again, or pick something else.",
        )

        expected.forEach { (code, sentence) ->
            val shown = PlaybackErrors.describe(context, PlaybackException(null, null, code), onTelevision = true)
            assertThat(shown.sentence).isEqualTo(sentence)
            assertThat(
                shown.detail,
            ).isEqualTo(readableCodeName(PlaybackException.getErrorCodeName(code)))
        }
        assertThat(readableCodeName("ERROR_CODE_PARSING_CONTAINER_MALFORMED"))
            .isEqualTo("Parsing container malformed")
    }

    @Test
    fun `nothing from the exception's own message reaches the sentence or the detail line`() {
        val secret = "http://example.com:8080/live/u/p/1.ts mac=00:1A:79:00:00:01"
        val format = Format.Builder().setId(secret).setLabel(secret).setSampleMimeType(MimeTypes.VIDEO_H265).build()
        val failures = listOf(
            ExoPlaybackException.createForSource(
                java.io.IOException(secret),
                PlaybackException.ERROR_CODE_IO_UNSPECIFIED,
            ),
            ExoPlaybackException.createForSource(
                UnknownHostException(secret),
                PlaybackException.ERROR_CODE_IO_UNSPECIFIED,
            ),
            PlaybackException(secret, IllegalStateException(secret), PlaybackException.ERROR_CODE_UNSPECIFIED),
            decoderFailure(format, cause = IllegalStateException(secret)),
            httpFailure(403),
        )

        failures.forEach { failure ->
            val shown = PlaybackErrors.describe(context, failure, onTelevision = true, format = format)
            listOf(shown.sentence, shown.detail).forEach { text ->
                assertThat(text).doesNotContain("example.com")
                assertThat(text).doesNotContain("/u/")
                assertThat(text).doesNotContain("/p/")
                assertThat(text).doesNotContain("00:1A:79")
            }
        }
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

        DecoderLog.logFailure(decoderFailure(format, cause = cause))

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

        DecoderLog.logFailure(decoderFailure(format))

        val logged = ShadowLog.getLogsForTag("OpenTV").single().msg
        assertThat(logged).contains("channels=6")
        assertThat(logged).contains("sampleRate=48000")
        assertThat(logged).doesNotContain("size=")
    }

    @Test
    fun `the video a decoder is given is logged by its named fields, even when nothing fails`() {
        val format = Format.Builder()
            .setId("http://example.com/u/p/1.ts")
            .setLabel("http://example.com/u/p/1.ts")
            .setSampleMimeType(MimeTypes.VIDEO_H265)
            .setCodecs("hvc1.2.4.L153.B0")
            .setWidth(3840)
            .setHeight(2160)
            .setAverageBitrate(23_800_000)
            .setFrameRate(25f)
            .build()

        DecoderLog.listener.onVideoInputFormatChanged(EVENT_TIME, format, null)

        val logged = ShadowLog.getLogsForTag("OpenTV").single().msg
        assertThat(logged).contains("video/hevc")
        assertThat(logged).contains("hvc1.2.4.L153.B0")
        assertThat(logged).contains("size=3840x2160")
        assertThat(logged).contains("bitDepth=10")
        assertThat(logged).contains("bitrate=23800000")
        assertThat(logged).contains("frameRate=25.0")
        assertThat(logged).doesNotContain("example.com")
    }

    @Test
    fun `a bitrate or frame rate the stream doesn't give is left out rather than logged as -1`() {
        val format = Format.Builder().setSampleMimeType(MimeTypes.VIDEO_H264).setWidth(1920).setHeight(1080).build()

        DecoderLog.listener.onVideoInputFormatChanged(EVENT_TIME, format, null)

        val logged = ShadowLog.getLogsForTag("OpenTV").single().msg
        assertThat(logged).contains("size=1920x1080")
        assertThat(logged).doesNotContain("bitrate")
        assertThat(logged).doesNotContain("frameRate")
    }

    @Test
    fun `the audio a decoder is given is logged with channels and sample rate`() {
        val format = Format.Builder()
            .setId("http://example.com/u/p/1.ts")
            .setSampleMimeType(MimeTypes.AUDIO_AAC)
            .setChannelCount(2)
            .setSampleRate(48_000)
            .build()

        DecoderLog.listener.onAudioInputFormatChanged(EVENT_TIME, format, null)

        val logged = ShadowLog.getLogsForTag("OpenTV").single().msg
        assertThat(logged).contains("audio/mp4a-latm")
        assertThat(logged).contains("channels=2")
        assertThat(logged).contains("sampleRate=48000")
        assertThat(logged).doesNotContain("size=")
        assertThat(logged).doesNotContain("example.com")
    }

    @Test
    fun `a failure that isn't the decoder's logs nothing`() {
        val error = PlaybackException("gone", null, PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED)

        DecoderLog.logFailure(error)

        assertThat(ShadowLog.getLogsForTag("OpenTV")).isEmpty()
    }

    private fun httpFailure(status: Int) = ExoPlaybackException.createForSource(
        HttpDataSource.InvalidResponseCodeException(
            status,
            null,
            null,
            emptyMap(),
            DataSpec(Uri.parse("http://example.com/live/u/p/1.ts")),
            ByteArray(0),
        ),
        PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS,
    )

    private fun unreachable() = ExoPlaybackException.createForSource(
        UnknownHostException("Unable to resolve host \"example.com\""),
        PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED,
    )

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

    private companion object {
        val EVENT_TIME = AnalyticsListener.EventTime(0, Timeline.EMPTY, 0, null, 0, Timeline.EMPTY, 0, null, 0, 0)
    }
}
