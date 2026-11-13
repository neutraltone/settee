/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv

import app.opentv.data.model.SubtitleKind
import app.opentv.data.remote.SubtitleResult
import app.opentv.data.repo.SubtitlePreference
import app.opentv.player.AutoSubtitles
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class AutoSubtitlesTest {

    private val wantsFrench = SubtitlePreference(enabled = true, language = "fr")

    private fun language(
        signedIn: Boolean = true,
        autoOn: Boolean = true,
        preference: SubtitlePreference? = wantsFrench,
        streamLanguages: List<String?> = listOf("en"),
        hasDownloaded: Boolean = false,
    ) = AutoSubtitles(device = "en").languageToFetch(signedIn, autoOn, preference, streamLanguages, hasDownloaded)

    @Test
    fun `fetches the preference's language when the stream lacks it and nothing is saved`() {
        assertThat(language()).isEqualTo("fr")
    }

    @Test
    fun `never fetches for a box that isn't signed in`() {
        assertThat(language(signedIn = false)).isNull()
    }

    @Test
    fun `never fetches with the switch off`() {
        assertThat(language(autoOn = false)).isNull()
    }

    @Test
    fun `never fetches when the Profile has subtitles off, or its preference isn't known yet`() {
        assertThat(language(preference = wantsFrench.copy(enabled = false))).isNull()
        assertThat(language(preference = null)).isNull()
    }

    @Test
    fun `never fetches when the stream has a track in that language, however it's written`() {
        assertThat(language(streamLanguages = listOf("en", "fre"))).isNull()
    }

    @Test
    fun `never fetches when the box already has a Downloaded Subtitle for the title`() {
        assertThat(language(hasDownloaded = true)).isNull()
    }

    @Test
    fun `with no language chosen, the device's language is the one looked for`() {
        assertThat(
            language(preference = SubtitlePreference(enabled = true, language = null), streamLanguages = listOf("de")),
        )
            .isEqualTo("en")
    }

    @Test
    fun `picks the most downloaded result of the preference's kind in that language`() {
        val results = listOf(
            SubtitleResult(1, "fr", "a", downloads = 900, kind = SubtitleKind.STANDARD),
            SubtitleResult(2, "fr", "b", downloads = 50, kind = SubtitleKind.HEARING_IMPAIRED),
            SubtitleResult(3, "fr", "c", downloads = 400, kind = SubtitleKind.HEARING_IMPAIRED),
            SubtitleResult(4, "en", "d", downloads = 9000, kind = SubtitleKind.HEARING_IMPAIRED),
        )

        assertThat(AutoSubtitles.pick(results, language = "fr", kind = SubtitleKind.HEARING_IMPAIRED)?.fileId)
            .isEqualTo(3L)
        assertThat(AutoSubtitles.pick(results, language = "fr", kind = SubtitleKind.FORCED)).isNull()
    }
}
