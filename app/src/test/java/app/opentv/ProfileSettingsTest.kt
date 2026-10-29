/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import app.opentv.data.db.OpenTvDatabase
import app.opentv.data.model.ProfileSetting
import app.opentv.data.repo.ProfileSettings
import app.opentv.data.repo.SubtitlePreference
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

// Plain Application: OpenTvApp schedules WorkManager on start, which the database doesn't need.
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class ProfileSettingsTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val db = Room.inMemoryDatabaseBuilder(context, OpenTvDatabase::class.java)
        .allowMainThreadQueries()
        .build()
    private val activeProfile = MutableStateFlow(1L)
    private var now = 1_000_000L
    private val settings = ProfileSettings(db.profileSettings(), activeProfile, clock = { now })

    @After
    fun close() = db.close()

    private suspend fun preference(): SubtitlePreference = settings.subtitlePreference.first()

    @Test
    fun `a Profile that has never chosen has subtitles off and follows the device language`() = runTest {
        assertThat(preference()).isEqualTo(SubtitlePreference(enabled = false, language = null))
        assertThat(settings.everyProfiles()).isEmpty()
    }

    @Test
    fun `turning subtitles on and then off is remembered`() = runTest {
        settings.setSubtitlesOn(true)
        assertThat(preference().enabled).isTrue()

        settings.setSubtitlesOn(false)
        assertThat(preference().enabled).isFalse()
    }

    @Test
    fun `picking a track turns subtitles on in that track's language`() = runTest {
        settings.pickSubtitleTrack(language = "fr")

        assertThat(preference()).isEqualTo(SubtitlePreference(enabled = true, language = "fr"))
    }

    @Test
    fun `the language is kept as its base code however the stream wrote it`() = runTest {
        settings.pickSubtitleTrack(language = "eng")
        assertThat(preference().language).isEqualTo("en")

        settings.pickSubtitleTrack(language = "pt-BR")
        assertThat(preference().language).isEqualTo("pt")
    }

    @Test
    fun `picking a track with no language keeps the language picked before`() = runTest {
        settings.pickSubtitleTrack(language = "fr")
        settings.setSubtitlesOn(false)

        settings.pickSubtitleTrack(language = null)

        assertThat(preference()).isEqualTo(SubtitlePreference(enabled = true, language = "fr"))
    }

    @Test
    fun `each Profile keeps its own Subtitle Preference`() = runTest {
        settings.pickSubtitleTrack(language = "fr")

        activeProfile.value = 2L
        assertThat(preference()).isEqualTo(SubtitlePreference(enabled = false, language = null))

        activeProfile.value = 1L
        assertThat(preference()).isEqualTo(SubtitlePreference(enabled = true, language = "fr"))
    }

    @Test
    fun `the Subtitle Preference survives a restart`() = runTest {
        settings.pickSubtitleTrack(language = "fr")

        val afterRestart = ProfileSettings(db.profileSettings(), MutableStateFlow(1L))

        assertThat(afterRestart.subtitlePreference.first())
            .isEqualTo(SubtitlePreference(enabled = true, language = "fr"))
    }

    @Test
    fun `deleting a Profile deletes its settings and no one else's`() = runTest {
        settings.pickSubtitleTrack(language = "fr")
        activeProfile.value = 2L
        settings.setSubtitlesOn(true)

        settings.forgetProfile(2L)

        assertThat(settings.everyProfiles().map { it.profileId }.distinct()).containsExactly(1L)
    }

    @Test
    fun `a newer choice from another device replaces this one`() = runTest {
        settings.pickSubtitleTrack(language = "fr")

        val taken = settings.mergeSynced(
            ProfileSetting(profileId = 1L, name = "subtitles.language", value = "de", updatedAtMillis = now + 1),
        )

        assertThat(taken).isTrue()
        assertThat(preference().language).isEqualTo("de")
    }

    @Test
    fun `an older choice from another device leaves this one alone`() = runTest {
        settings.pickSubtitleTrack(language = "fr")

        val taken = settings.mergeSynced(
            ProfileSetting(profileId = 1L, name = "subtitles.language", value = "de", updatedAtMillis = now - 1),
        )

        assertThat(taken).isFalse()
        assertThat(preference().language).isEqualTo("fr")
    }
}
