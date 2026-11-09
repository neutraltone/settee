/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv

import app.opentv.player.DecoderList
import app.opentv.player.DecoderSize

/** A device whose only video decoders are these, each by the codec it's for and the largest size it lists. */
class FakeDecoders(private vararg val decoders: Pair<String, DecoderSize>) : DecoderList {
    override fun sizesFor(mimeType: String) = decoders.filter { it.first == mimeType }.map { it.second }
}

/** A device that plays any Quality, for the tests that aren't about what a device can decode. */
val ANY_SIZE_DECODERS = FakeDecoders(
    "video/avc" to DecoderSize(7680, 4320),
)
