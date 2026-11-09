/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv.player

import androidx.annotation.OptIn
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import java.util.Locale

/** What a viewer would call this format, such as "HEVC 10-bit". Depth is named only when it's above 8-bit. */
@get:OptIn(UnstableApi::class)
internal val Format.displayName: String
    get() {
        val mimeType = sampleMimeType.orEmpty()
        val name = FormatNames.byMimeType[mimeType] ?: mimeType.substringAfter('/').uppercase(Locale.ROOT)
        return name + bitDepth?.takeIf { it > EIGHT_BIT }?.let { " $it-bit" }.orEmpty()
    }

/** [displayName] with the picture's size, when the stream has said what it is: "HEVC, 3840x1600". */
@get:OptIn(UnstableApi::class)
internal val Format.displayNameAndSize: String
    get() = displayName + if (width > 0 && height > 0) ", ${width}x$height" else ""

/**
 * Containers fill in the colour info or the codec string, rarely both, and a high bit depth is
 * usually what a cheap box's decoder can't do, so both are read. Null when neither says.
 */
@get:OptIn(UnstableApi::class)
internal val Format.bitDepth: Int?
    get() = colorInfo?.lumaBitdepth?.takeIf { it != Format.NO_VALUE }
        ?: MimeTypes.getCodecsCorrespondingToMimeType(codecs, sampleMimeType)?.let(::bitDepthInCodecs)

private fun bitDepthInCodecs(codecs: String): Int? {
    val fields = codecs.substringBefore(',').split('.')
    val profile = fields.getOrNull(1).orEmpty()
    return when (fields.first()) {
        // RFC 6381: profile 2 is Main 10, after an optional letter for the profile space. Range
        // Extensions (profile 4) can be several depths, which the profile alone doesn't say.
        "hvc1", "hev1" -> TEN_BIT.takeIf { profile.trimStart('A', 'B', 'C') == "2" }
        // The profile's first two hex digits; 6E is High 10.
        "avc1", "avc3" -> TEN_BIT.takeIf { profile.startsWith("6E", ignoreCase = true) }
        "av01", "vp09" -> fields.getOrNull(BIT_DEPTH_FIELD)?.toIntOrNull()
        else -> null
    }
}

private const val EIGHT_BIT = 8
private const val TEN_BIT = 10

/** "av01.0.08M.10" and "vp09.02.10.10" both keep the bit depth in their fourth field. */
private const val BIT_DEPTH_FIELD = 3

@OptIn(UnstableApi::class)
private object FormatNames {
    val byMimeType = mapOf(
        MimeTypes.VIDEO_H264 to "H.264",
        MimeTypes.VIDEO_H265 to "HEVC",
        MimeTypes.VIDEO_AV1 to "AV1",
        MimeTypes.VIDEO_VP8 to "VP8",
        MimeTypes.VIDEO_VP9 to "VP9",
        MimeTypes.VIDEO_MPEG2 to "MPEG-2",
        MimeTypes.VIDEO_VC1 to "VC-1",
        MimeTypes.VIDEO_DOLBY_VISION to "Dolby Vision",
        MimeTypes.VIDEO_MP4V to "MPEG-4",
        MimeTypes.VIDEO_H263 to "H.263",
        MimeTypes.AUDIO_AAC to "AAC",
        MimeTypes.AUDIO_MPEG to "MP3",
        MimeTypes.AUDIO_MPEG_L2 to "MP2",
        MimeTypes.AUDIO_AC3 to "AC-3",
        MimeTypes.AUDIO_E_AC3 to "E-AC-3",
        MimeTypes.AUDIO_E_AC3_JOC to "E-AC-3 JOC",
        MimeTypes.AUDIO_AC4 to "AC-4",
        MimeTypes.AUDIO_TRUEHD to "TrueHD",
        MimeTypes.AUDIO_DTS to "DTS",
        MimeTypes.AUDIO_DTS_HD to "DTS-HD",
        MimeTypes.AUDIO_DTS_EXPRESS to "DTS Express",
        MimeTypes.AUDIO_DTS_X to "DTS:X",
        MimeTypes.AUDIO_OPUS to "Opus",
        MimeTypes.AUDIO_VORBIS to "Vorbis",
        MimeTypes.AUDIO_FLAC to "FLAC",
    )
}
