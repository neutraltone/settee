/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv

import androidx.media3.common.MimeTypes
import app.opentv.data.repo.titleBadge
import app.opentv.data.repo.versionTraits
import app.opentv.player.DecoderSize
import com.google.common.truth.Truth.assertThat
import org.junit.Test

/** One badge per cover: the best known Quality among the Versions this device can play (#181). */
class TitleBadgeTest {

    private val stick = FakeDecoders(MimeTypes.VIDEO_H265 to DecoderSize(1920, 1088))
    private val fourK = FakeDecoders(MimeTypes.VIDEO_H265 to DecoderSize(3840, 2160))

    private fun badge(vararg names: String, decoders: FakeDecoders = stick) =
        titleBadge(names.map { versionTraits(it, null) }, decoders)

    @Test
    fun `the best Quality this device can play is the badge`() {
        assertThat(badge("4K-EN - Superbad", "EN - Superbad FHD", "NF - Superbad")).isEqualTo("FHD")
        assertThat(badge("4K-EN - Superbad", "EN - Superbad FHD", decoders = fourK)).isEqualTo("4K")
        assertThat(badge("EN - Superbad HD", "NF - Superbad")).isEqualTo("HD")
    }

    @Test
    fun `a title whose playable Versions are all untagged has no badge`() {
        assertThat(badge("NF - Superbad", "AMZ - Superbad")).isNull()
        assertThat(badge("4K-EN - Superbad", "NF - Superbad")).isNull()
    }

    @Test
    fun `CAM only when nothing but cam copies play`() {
        assertThat(badge("4K-EN - New Film", "EN - New Film HDCAM")).isEqualTo("CAM")
        assertThat(badge("EN - New Film HDCAM", "NF - New Film")).isNull()
    }

    @Test
    fun `a title with nothing this device can play shows 4K`() {
        assertThat(badge("4K-EN - Superbad", "4K-TOP - Superbad")).isEqualTo("4K")
    }
}
