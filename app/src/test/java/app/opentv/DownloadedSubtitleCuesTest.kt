/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv

import app.opentv.player.DownloadedSubtitleCues
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

// Robolectric: the SRT's styling is read with Android's Html.
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class DownloadedSubtitleCuesTest {

    private val cues = DownloadedSubtitleCues.parse(
        """
        1
        00:00:01,000 --> 00:00:02,000
        Hello

        2
        00:00:05,000 --> 00:00:06,500
        <i>Goodbye</i>
        """.trimIndent(),
    )

    private fun shown(positionMillis: Long, offsetMillis: Long = 0) =
        cues.at(positionMillis, offsetMillis).map { it.text.toString() }

    @Test
    fun `a line shows from its start to its end`() {
        assertThat(shown(900)).isEmpty()
        assertThat(shown(1_500)).containsExactly("Hello")
        assertThat(shown(2_100)).isEmpty()
        assertThat(shown(5_200)).containsExactly("Goodbye")
    }

    @Test
    fun `a positive offset shows every line later`() {
        assertThat(shown(1_100, offsetMillis = 300)).isEmpty()
        assertThat(shown(2_200, offsetMillis = 300)).containsExactly("Hello")
    }

    @Test
    fun `a negative offset shows every line earlier`() {
        assertThat(shown(600, offsetMillis = -500)).containsExactly("Hello")
        assertThat(shown(1_700, offsetMillis = -500)).isEmpty()
    }

    @Test
    fun `a file that isn't SRT shows nothing rather than failing`() {
        assertThat(DownloadedSubtitleCues.parse("not a subtitle").at(1_000, 0)).isEmpty()
    }
}
