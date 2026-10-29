/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv

import app.opentv.data.repo.SubtitlePreference
import app.opentv.player.StreamSubtitles
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class StreamSubtitlesTest {

    private val wantsFrench = SubtitlePreference(enabled = true, language = "fr")
    private val wantsNone = SubtitlePreference(enabled = false, language = null)
    private val stream = StreamSubtitles()

    @Test
    fun `a Profile that wants subtitles gets them once the stream's text tracks arrive`() {
        assertThat(stream.shouldTurnOn(wantsFrench, hasText = false)).isFalse()

        assertThat(stream.shouldTurnOn(wantsFrench, hasText = true)).isTrue()
    }

    @Test
    fun `turning subtitles off part way through a title is not undone`() {
        stream.shouldTurnOn(wantsFrench, hasText = true)

        // The picker turned text off before the saved preference caught up.
        assertThat(stream.shouldTurnOn(wantsFrench, hasText = true)).isFalse()
    }

    @Test
    fun `the next channel or title starts from the Profile's preference again`() {
        stream.shouldTurnOn(wantsFrench, hasText = true)

        stream.newStream()

        assertThat(stream.shouldTurnOn(wantsFrench, hasText = true)).isTrue()
    }

    @Test
    fun `nothing is decided until the Profile's preference has loaded`() {
        assertThat(stream.shouldTurnOn(null, hasText = true)).isFalse()

        assertThat(stream.shouldTurnOn(wantsFrench, hasText = true)).isTrue()
    }

    @Test
    fun `a Profile that wants no subtitles never has them turned on for it`() {
        assertThat(stream.shouldTurnOn(wantsNone, hasText = true)).isFalse()
        assertThat(stream.shouldTurnOn(wantsFrench, hasText = true)).isFalse()
    }
}
