/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv

import android.media.MediaCodecInfo
import android.media.MediaFormat
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.mediacodec.MediaCodecUtil
import org.robolectric.shadows.MediaCodecInfoBuilder
import org.robolectric.shadows.ShadowMediaCodec
import org.robolectric.shadows.ShadowMediaCodecList

/*
 * The device's real decoder list under Robolectric, for the tests that play on real renderers.
 */

/** Adds a video decoder to the device's list, first added being first tried. */
fun listVideoDecoder(name: String, mimeType: String, width: Int, height: Int) {
    ShadowMediaCodecList.addCodec(
        MediaCodecInfoBuilder.newBuilder()
            .setName(name)
            .setCapabilities(
                MediaCodecInfoBuilder.CodecCapabilitiesBuilder.newBuilder()
                    .setMediaFormat(MediaFormat.createVideoFormat(mimeType, width, height))
                    .setColorFormats(intArrayOf(MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible))
                    .build(),
            )
            .build(),
    )
}

/** Leaves the device with no decoders, and Media3 with no memory of the ones it had. */
@UnstableApi
fun forgetVideoDecoders() {
    ShadowMediaCodec.clearCodecs()
    ShadowMediaCodecList.reset()
    MediaCodecUtil.clearDecoderInfoCache()
}
