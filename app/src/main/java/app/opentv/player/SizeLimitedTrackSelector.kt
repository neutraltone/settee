/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv.player

import android.content.Context
import android.util.Pair
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.RendererCapabilities
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector
import androidx.media3.exoplayer.trackselection.ExoTrackSelection
import androidx.media3.exoplayer.trackselection.MappingTrackSelector

/**
 * Leaves a video track above every decoder's listed size unselected, so no decoder ever starts on it.
 *
 * Not `setExceedRendererCapabilitiesIfNecessary(false)`, the obvious switch: that also refuses on
 * profile, level and frame rate, and streams that play today would stop. Waiting for `onTracksChanged`
 * is too late, because a decoder is already starting by then. A track left unselected raises no
 * error, so [PlayerController] says so itself.
 */
@OptIn(UnstableApi::class)
internal class SizeLimitedTrackSelector(context: Context, private val decoders: DecoderList) :
    DefaultTrackSelector(context) {

    override fun selectVideoTrack(
        mappedTrackInfo: MappingTrackSelector.MappedTrackInfo,
        rendererFormatSupports: Array<Array<IntArray>>,
        mixedMimeTypeSupports: IntArray,
        params: Parameters,
    ): Pair<ExoTrackSelection.Definition, Int>? {
        // A copy: the player reads the originals back to say which tracks this device supports.
        val limited = Array(rendererFormatSupports.size) { renderer ->
            val supports = rendererFormatSupports[renderer]
            val isVideo = renderer < mappedTrackInfo.rendererCount &&
                mappedTrackInfo.getRendererType(renderer) == C.TRACK_TYPE_VIDEO
            if (isVideo) refusingOversize(mappedTrackInfo, renderer, supports) else supports
        }
        return super.selectVideoTrack(mappedTrackInfo, limited, mixedMimeTypeSupports, params)
    }

    private fun refusingOversize(
        info: MappingTrackSelector.MappedTrackInfo,
        renderer: Int,
        supports: Array<IntArray>,
    ): Array<IntArray> = Array(supports.size) { group ->
        val trackGroup = info.getTrackGroups(renderer)[group]
        IntArray(supports[group].size) { track ->
            if (decoders.refuses(trackGroup.getFormat(track))) {
                RendererCapabilities.create(C.FORMAT_UNSUPPORTED_SUBTYPE)
            } else {
                supports[group][track]
            }
        }
    }
}
