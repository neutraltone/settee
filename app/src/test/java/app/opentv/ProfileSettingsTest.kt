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
import app.opentv.data.model.SubtitleKind
import app.opentv.data.repo.HomeArrangement
import app.opentv.data.repo.HomeRowKind
import app.opentv.data.repo.HomeRowKind.BECAUSE_YOU_WATCHED
import app.opentv.data.repo.HomeRowKind.CONTINUE_WATCHING
import app.opentv.data.repo.HomeRowKind.FAVOURITE_CHANNELS
import app.opentv.data.repo.HomeRowKind.FAVOURITE_MOVIES
import app.opentv.data.repo.HomeRowKind.FAVOURITE_SHOWS
import app.opentv.data.repo.HomeRowKind.RECENT_MOVIES
import app.opentv.data.repo.HomeRowKind.RECENT_SHOWS
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
    private val settings = ProfileSettings(db, activeProfile, clock = { now })

    @After
    fun close() = db.close()

    private suspend fun preference(): SubtitlePreference = settings.subtitlePreference.first()

    @Test
    fun `a Profile that has never chosen has subtitles off and follows the device language`() = runTest {
        assertThat(preference()).isEqualTo(SubtitlePreference(enabled = false, language = null))
        assertThat(db.profileSettings().all()).isEmpty()
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
    fun `picking a Downloaded Subtitle remembers its language and its kind`() = runTest {
        settings.pickSubtitleTrack(language = "de", kind = SubtitleKind.HEARING_IMPAIRED)

        assertThat(preference())
            .isEqualTo(SubtitlePreference(enabled = true, language = "de", kind = SubtitleKind.HEARING_IMPAIRED))
    }

    @Test
    fun `picking a stream's own track changes the language but leaves the kind alone`() = runTest {
        settings.pickSubtitleTrack(language = "de", kind = SubtitleKind.FORCED)

        settings.pickSubtitleTrack(language = "fr")

        assertThat(
            preference(),
        ).isEqualTo(SubtitlePreference(enabled = true, language = "fr", kind = SubtitleKind.FORCED))
    }

    @Test
    fun `a Profile that never picked from OpenSubtitles wants standard subtitles`() = runTest {
        settings.pickSubtitleTrack(language = "fr")

        assertThat(preference().kind).isEqualTo(SubtitleKind.STANDARD)
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

        val afterRestart = ProfileSettings(db, MutableStateFlow(1L))

        assertThat(afterRestart.subtitlePreference.first())
            .isEqualTo(SubtitlePreference(enabled = true, language = "fr"))
    }

    @Test
    fun `deleting a Profile deletes its settings and no one else's`() = runTest {
        settings.pickSubtitleTrack(language = "fr")
        activeProfile.value = 2L
        settings.setSubtitlesOn(true)

        settings.forgetProfile(2L)

        assertThat(db.profileSettings().all().map { it.profileId }.distinct()).containsExactly(1L)
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

    private suspend fun home(): HomeArrangement = settings.homeArrangement.first()

    private val usualRows = listOf(
        CONTINUE_WATCHING,
        BECAUSE_YOU_WATCHED,
        RECENT_SHOWS,
        RECENT_MOVIES,
        FAVOURITE_SHOWS,
        FAVOURITE_MOVIES,
        FAVOURITE_CHANNELS,
    )

    /** Favourite channels moved to the top, the rest in their usual order. */
    private val channelsFirst = listOf(
        FAVOURITE_CHANNELS,
        CONTINUE_WATCHING,
        BECAUSE_YOU_WATCHED,
        RECENT_SHOWS,
        RECENT_MOVIES,
        FAVOURITE_SHOWS,
        FAVOURITE_MOVIES,
    )

    @Test
    fun `a Profile that never arranged Home gets the usual rows, all shown, with the Live Preview`() = runTest {
        assertThat(home()).isEqualTo(HomeArrangement(usualRows, hidden = emptySet(), livePreview = true))
        assertThat(db.profileSettings().all()).isEmpty()
    }

    @Test
    fun `moving and hiding Home's rows is remembered across a restart`() = runTest {
        val arranged = HomeArrangement(
            channelsFirst,
            hidden = setOf(RECENT_MOVIES),
            livePreview = false,
        )

        settings.arrangeHome(arranged)
        val afterRestart = ProfileSettings(db, MutableStateFlow(1L))

        assertThat(afterRestart.homeArrangement.first()).isEqualTo(arranged)
    }

    @Test
    fun `each Profile arranges its own Home`() = runTest {
        settings.arrangeHome(HomeArrangement(usualRows.reversed(), hidden = emptySet(), livePreview = true))

        activeProfile.value = 2L

        assertThat(home().rows).isEqualTo(usualRows)
    }

    @Test
    fun `restoring the usual order shows every row and the Live Preview again`() = runTest {
        settings.arrangeHome(HomeArrangement(usualRows.reversed(), hidden = setOf(RECENT_SHOWS), livePreview = false))

        settings.arrangeHome(HomeArrangement.USUAL)

        assertThat(home()).isEqualTo(HomeArrangement(usualRows, hidden = emptySet(), livePreview = true))
    }

    @Test
    fun `a row missing from a stored arrangement goes last and shown, and a row Home no longer knows is dropped`() =
        runTest {
            // Written by an older or newer release: no Favourite movies yet, and a row since retired.
            db.profileSettings().upsert(
                ProfileSetting(1L, "home.rows", "preview=off;channels,-cw,because,retiredRow,recentShows", now),
            )

            assertThat(home()).isEqualTo(
                HomeArrangement(
                    channelsFirst,
                    hidden = setOf(CONTINUE_WATCHING),
                    livePreview = false,
                ),
            )
        }

    @Test
    fun `a stored arrangement that can't be read is the usual one`() = runTest {
        db.profileSettings().upsert(ProfileSetting(1L, "home.rows", "nonsense", now))

        assertThat(home()).isEqualTo(HomeArrangement(usualRows, hidden = emptySet(), livePreview = true))
    }

    @Test
    fun `a hidden row keeps its place, so showing it puts it back where it was`() = runTest {
        val hidden = HomeArrangement(usualRows, hidden = emptySet(), livePreview = true)
            .withShown(FAVOURITE_SHOWS, false)

        assertThat(hidden.withShown(FAVOURITE_SHOWS, true).rows).isEqualTo(usualRows)
        assertThat(hidden.rows).isEqualTo(usualRows)
        assertThat(hidden.hidden).containsExactly(FAVOURITE_SHOWS)
    }

    @Test
    fun `the Home Rows' keys are the ones Home's rows use`() {
        assertThat(HomeRowKind.entries.map { it.key })
            .containsExactly(
                "cw",
                "because",
                "recentShows",
                "recentMovies",
                "favouriteShows",
                "favouriteMovies",
                "channels",
            )
            .inOrder()
    }
}
