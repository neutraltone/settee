/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv

import app.opentv.player.SubtitlePick
import app.opentv.player.SubtitleTrack
import app.opentv.player.chooseSubtitle
import app.opentv.player.chooseSubtitleTrack
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class SubtitleTrackChoiceTest {

    private fun tracks(vararg languages: String?) = languages.map { SubtitleTrack(it, supported = true) }

    @Test
    fun `the language the Profile picked wins over the device language`() {
        val choice = chooseSubtitleTrack(tracks("en", "fr", "de"), preferred = "fr", device = "en")

        assertThat(choice).isEqualTo(1)
    }

    @Test
    fun `a Profile that never picked a language gets the device language`() {
        val choice = chooseSubtitleTrack(tracks("fr", "en", "de"), preferred = null, device = "en")

        assertThat(choice).isEqualTo(1)
    }

    @Test
    fun `a title without either language still shows the first track it can`() {
        val unsupported = SubtitleTrack("es", supported = false)
        val choice = chooseSubtitleTrack(
            listOf(unsupported) + tracks("it", "de"),
            preferred = "fr",
            device = "en",
        )

        assertThat(choice).isEqualTo(1)
    }

    @Test
    fun `codes for the same language match however the stream writes them`() {
        assertThat(chooseSubtitleTrack(tracks("fr", "eng"), preferred = null, device = "en")).isEqualTo(1)
        assertThat(chooseSubtitleTrack(tracks("fr", "en-GB"), preferred = "en", device = "de")).isEqualTo(1)
    }

    @Test
    fun `a Downloaded Subtitle in the Profile's language is chosen when the stream has none in it`() {
        val choice = chooseSubtitle(tracks("en"), downloaded = listOf("de", "fr"), preferred = "fr", device = "en")

        assertThat(choice).isEqualTo(SubtitlePick.Downloaded(1))
    }

    @Test
    fun `the stream's own track wins over a Downloaded Subtitle in the same language`() {
        val choice = chooseSubtitle(tracks("en", "fr"), downloaded = listOf("fr"), preferred = "fr", device = "en")

        assertThat(choice).isEqualTo(SubtitlePick.Stream(1))
    }

    @Test
    fun `a Downloaded Subtitle in the device language beats a stream track in neither`() {
        val choice = chooseSubtitle(tracks("it"), downloaded = listOf("en"), preferred = "fr", device = "en")

        assertThat(choice).isEqualTo(SubtitlePick.Downloaded(0))
    }

    @Test
    fun `with nothing in either language, the stream's first track is still shown`() {
        assertThat(chooseSubtitle(tracks("it"), downloaded = listOf("es"), preferred = "fr", device = "en"))
            .isEqualTo(SubtitlePick.Stream(0))
        assertThat(chooseSubtitle(emptyList(), downloaded = emptyList(), preferred = "fr", device = "en")).isNull()
    }
}
