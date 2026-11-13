/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv

import app.opentv.data.model.SubtitleKind
import app.opentv.data.remote.SubtitleResult
import app.opentv.player.orderSubtitleResults
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class SubtitleResultOrderTest {

    private fun result(id: Long, language: String, downloads: Int) =
        SubtitleResult(id, language, release = "r$id", downloads = downloads, kind = SubtitleKind.STANDARD)

    private val results = listOf(
        result(1, "de", 5000),
        result(2, "en", 10),
        result(3, "fr", 300),
        result(4, "en", 900),
        result(5, "fr", 20),
        result(6, "es", 70),
    )

    @Test
    fun `the preference's language comes first, then the device's, then the rest, each by downloads`() {
        val ordered = orderSubtitleResults(results, preferred = "fr", device = "en")

        assertThat(ordered.map { it.fileId }).containsExactly(3L, 5L, 4L, 2L, 1L, 6L).inOrder()
    }

    @Test
    fun `with no preferred language the device's language leads`() {
        val ordered = orderSubtitleResults(results, preferred = null, device = "en")

        assertThat(ordered.map { it.fileId }).containsExactly(4L, 2L, 1L, 3L, 6L, 5L).inOrder()
    }

    @Test
    fun `languages match whatever form the code comes in`() {
        // Subtitle Preferences hold what the stream's track said (eng, en-GB); OpenSubtitles uses en.
        val ordered = orderSubtitleResults(results, preferred = "eng", device = "de-DE")

        assertThat(ordered.map { it.fileId }.take(3)).containsExactly(4L, 2L, 1L).inOrder()
    }
}
