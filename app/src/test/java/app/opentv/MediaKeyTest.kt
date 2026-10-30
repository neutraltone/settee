/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv

import app.opentv.data.repo.MediaKey
import com.google.common.truth.Truth.assertThat
import org.junit.Test

/** The strings keys are stored as in playback_positions and sync never change (#98). */
class MediaKeyTest {

    private val stored = mapOf(
        "movie:5" to MediaKey.Movie(5),
        "ep:7" to MediaKey.Episode(7),
        "rec:3" to MediaKey.Recording(3),
        "catchup:9:1699995600000" to MediaKey.Catchup(9, 1_699_995_600_000L),
    )

    @Test
    fun `each kind of key is stored as it always has been`() {
        assertThat(stored.values.map { it.stored }).containsExactlyElementsIn(stored.keys).inOrder()
    }

    @Test
    fun `a stored key reads back as the key it was`() {
        assertThat(stored.keys.map(MediaKey::parse)).containsExactlyElementsIn(stored.values).inOrder()
    }

    @Test
    fun `an add-on stream keeps its progress on the film it plays`() {
        assertThat(MediaKey.AddonStream(5, "https://example.org/drive.mp4").stored).isEqualTo("movie:5")
    }

    @Test
    fun `a string that isn't a key reads back as nothing`() {
        assertThat(listOf("", "movie:", "ep:x", "show:4", "catchup:9").map(MediaKey::parse)).containsExactly(
            null,
            null,
            null,
            null,
            null,
        )
    }
}
