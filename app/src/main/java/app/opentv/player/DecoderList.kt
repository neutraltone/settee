/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv.player

import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.Tracks
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.mediacodec.MediaCodecUtil
import app.opentv.data.parser.ChannelNameNormalizer

/** The largest video a decoder says it can take. */
data class DecoderSize(val width: Int, val height: Int)

/** What this device's video decoders say they can take. Passed in so a test needn't have real ones. */
fun interface DecoderList {
    /** One size per decoder for [mimeType]; none when the device has no such decoder. */
    fun sizesFor(mimeType: String): List<DecoderSize>

    /** The tallest video an H.264 or HEVC decoder lists. A Quality's name doesn't say which codec it uses. */
    val tallestListed: Int
        get() = (sizesFor(MimeTypes.VIDEO_H264) + sizesFor(MimeTypes.VIDEO_H265)).maxOfOrNull { it.height } ?: 0
}

/**
 * Whether a Quality ranked [qualityRank] could play here. Only its tag says how big it is, so one with
 * no tag counts. Only asks the device about a Quality tagged 4K or above, so a catalogue without one never does.
 */
fun DecoderList.playsQualityRanked(qualityRank: Int): Boolean {
    val height = ChannelNameNormalizer.heightPromisedBy(qualityRank) ?: return true
    return tallestListed >= height
}

/**
 * Whether [format] is above every decoder listed for its codec, which also counts the codec a Dolby
 * Vision stream falls back on. There's no tolerance for a stream slightly over, and a codec with no
 * decoder at all isn't this rule's business: Media3 already reports that.
 */
@OptIn(UnstableApi::class)
internal fun DecoderList.refuses(format: Format): Boolean {
    val mimeType = format.sampleMimeType?.takeIf(MimeTypes::isVideo) ?: return false
    val alternative = MediaCodecUtil.getAlternativeCodecMimeType(format)
    val sizes = sizesFor(mimeType) + alternative?.let(::sizesFor).orEmpty()
    // A size of its own not said yet leaves nothing to measure.
    val sized = format.width > 0 && format.height > 0
    return sized && sizes.isNotEmpty() && sizes.none { format.width <= it.width && format.height <= it.height }
}

/**
 * The first of these video tracks, when none of them can play: each above every decoder's size, or with
 * no decoder for it at all. Either way the player leaves video unselected and raises no error, so its
 * clock runs over a blank screen (#180, a Dolby Vision profile 5 stream has no fallback codec).
 */
internal fun DecoderList.refusedVideoOf(tracks: Tracks): Format? {
    val video = tracks.groups.filter { it.type == C.TRACK_TYPE_VIDEO }
    val playable = video.any { group ->
        (0 until group.length).any { group.isTrackSupported(it, true) && !refuses(group.getTrackFormat(it)) }
    }
    return if (playable) null else video.firstOrNull()?.getTrackFormat(0)
}

/**
 * The decoders on this device. The tallest is kept: a catalogue can hold thousands of rows tagged 4K or
 * 8K, each asking on every fold, and a device's decoders don't change while the app runs.
 */
@OptIn(UnstableApi::class)
class DeviceDecoders : DecoderList {
    override val tallestListed: Int by lazy { super.tallestListed }

    override fun sizesFor(mimeType: String): List<DecoderSize> = try {
        MediaCodecUtil.getDecoderInfos(mimeType, false, false).map { decoder ->
            val video = decoder.capabilities?.videoCapabilities
            // A decoder that lists nothing hasn't ruled anything out.
            if (video == null) {
                DecoderSize(Int.MAX_VALUE, Int.MAX_VALUE)
            } else {
                DecoderSize(video.supportedWidths.upper, video.supportedHeights.upper)
            }
        }
    } catch (_: MediaCodecUtil.DecoderQueryException) {
        emptyList()
    }
}
