/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv

import android.app.Application
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import app.opentv.core.ServiceLocator
import app.opentv.data.db.OpenTvDatabase
import app.opentv.data.model.Channel
import app.opentv.data.model.Favourite
import app.opentv.data.model.Profile
import app.opentv.data.model.ProfileSetting
import app.opentv.data.model.Recording
import app.opentv.data.model.RecordingStatus
import app.opentv.data.model.SeriesDismissal
import app.opentv.data.model.Source
import app.opentv.data.model.SourceKind
import app.opentv.data.model.StreamKind
import app.opentv.data.repo.Profiles
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

// Plain Application: OpenTvApp schedules WorkManager on start, which the database doesn't need.
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class ProfilesTest {

    private val app: Application = ApplicationProvider.getApplicationContext()
    private val db = Room.inMemoryDatabaseBuilder(app, OpenTvDatabase::class.java)
        .allowMainThreadQueries()
        .build()
    private val graph = ServiceLocator.Graph(app) { db }
    private val profiles get() = graph.profiles

    // Settings are process-wide, so each test starts on the base Profile whatever the last one left.
    @Before
    fun startOnBaseProfile() = graph.settings.setActiveProfile(Profiles.BASE_PROFILE_ID)

    @After
    fun close() = db.close()

    @Test
    fun `a fresh install has one profile, Me, whatever asks first`() = runTest {
        assertThat(profiles.observeAll().first().map { it.name }).containsExactly("Me")
        assertThat(profiles.all().map { it.id }).containsExactly(Profiles.BASE_PROFILE_ID)
    }

    @Test
    fun `a profile added before anything reads the list doesn't become the base profile`() = runTest {
        val tom = checkNotNull(profiles.create("Tom"))

        assertThat(tom).isNotEqualTo(Profiles.BASE_PROFILE_ID)
        assertThat(profiles.all().map { it.name }).containsExactly("Me", "Tom").inOrder()
    }

    @Test
    fun `a removed profile leaves nothing behind in any table that keeps per-profile rows`() = runTest {
        val tom = checkNotNull(profiles.create("Tom"))
        giveEveryPerProfileTableARowFor(tom)

        assertThat(profiles.remove(tom)).isTrue()

        for (table in tablesWithAProfileId()) {
            assertWithMessage(table).that(rowsFor(table, tom)).isEqualTo(0)
        }
        assertThat(profiles.all().map { it.name }).containsExactly("Me")
    }

    /**
     * Fails when a table gains a `profileId` column: seed it in [giveEveryPerProfileTableARowFor]
     * and give the store that owns it a forget step that `Profiles.remove` calls.
     */
    @Test
    fun `every table with a profile id is one the removal test fills`() {
        assertThat(tablesWithAProfileId()).containsExactlyElementsIn(SEEDED_TABLES)
    }

    @Test
    fun `a removed profile's recordings stay, for no one in particular`() = runTest {
        val tom = checkNotNull(profiles.create("Tom"))
        val id = graph.recordingRepository.insert(recording(profileId = tom))

        profiles.remove(tom)

        val kept = checkNotNull(graph.recordingRepository.byId(id))
        assertThat(kept.profileId).isNull()
    }

    @Test
    fun `the base profile can't be removed`() = runTest {
        profiles.all()

        assertThat(profiles.remove(Profiles.BASE_PROFILE_ID)).isFalse()
        assertThat(profiles.all().map { it.id }).containsExactly(Profiles.BASE_PROFILE_ID)
    }

    @Test
    fun `removing the active profile makes the base profile active`() = runTest {
        val tom = checkNotNull(profiles.create("Tom"))
        graph.settings.setActiveProfile(tom)

        profiles.remove(tom)

        assertThat(graph.settings.activeProfileId.value).isEqualTo(Profiles.BASE_PROFILE_ID)
    }

    @Test
    fun `removing another profile leaves the active one alone`() = runTest {
        val tom = checkNotNull(profiles.create("Tom"))
        val sam = checkNotNull(profiles.create("Sam"))
        graph.settings.setActiveProfile(sam)

        profiles.remove(tom)

        assertThat(graph.settings.activeProfileId.value).isEqualTo(sam)
    }

    @Test
    fun `a name another profile has is refused, ignoring case and spaces`() = runTest {
        profiles.create("Tom")

        assertThat(profiles.create(" tom ")).isNull()
        assertThat(profiles.all().map { it.name }).containsExactly("Me", "Tom")
    }

    @Test
    fun `renaming to another profile's name is refused, but a profile keeps its own`() = runTest {
        val tom = checkNotNull(profiles.create("Tom"))

        assertThat(profiles.rename(tom, "me")).isFalse()
        assertThat(profiles.rename(tom, "TOM")).isTrue()
        assertThat(profiles.all().map { it.name }).containsExactly("Me", "TOM")
    }

    @Test
    fun `renaming or removing a profile that isn't there reports nothing done`() = runTest {
        profiles.all()

        assertThat(profiles.rename(99, "Tom")).isFalse()
        assertThat(profiles.remove(99)).isFalse()
    }

    @Test
    fun `a blank name is refused`() = runTest {
        assertThat(profiles.create("   ")).isNull()
        assertThat(profiles.rename(Profiles.BASE_PROFILE_ID, "")).isFalse()
    }

    @Test
    fun `names are kept without surrounding spaces`() = runTest {
        val tom = checkNotNull(profiles.create("  Tom "))

        assertThat(profiles.all().single { it.id == tom }.name).isEqualTo("Tom")
    }

    @Test
    fun `a new profile records when it was created`() = runTest {
        val profiles = with(graph) {
            Profiles(db.profiles(), viewerLibrary, profileSettings, recordingRepository, settings, clock = { 42_000L })
        }

        val tom = checkNotNull(profiles.create("Tom"))

        assertThat(profiles.all().map { it.createdAtMillis }).containsExactly(42_000L, 42_000L)
        assertThat(profiles.all().single { it.id == tom }.createdAtMillis).isEqualTo(42_000L)
    }

    @Test
    fun `sync finds a profile by name the way the name check matches, and creates a missing one`() = runTest {
        val tom = checkNotNull(profiles.create("Tom"))

        assertThat(profiles.findOrCreate("tom")).isEqualTo(tom)
        val sam = checkNotNull(profiles.findOrCreate("Sam"))
        assertThat(profiles.findOrCreate("Sam")).isEqualTo(sam)
        assertThat(profiles.findOrCreate(" ")).isNull()
        assertThat(profiles.all().map { it.name }).containsExactly("Me", "Tom", "Sam")
    }

    @Test
    fun `the name check ignores the profile being renamed`() {
        val among = listOf(Profile(id = 2, name = "Tom"))

        assertThat(Profiles.isNameTaken("tom", among)).isTrue()
        assertThat(Profiles.isNameTaken("tom", among, except = 2)).isFalse()
    }

    private suspend fun giveEveryPerProfileTableARowFor(profileId: Long) {
        val source = db.sources().insert(Source(name = "Provider", kind = SourceKind.M3U, url = "https://example.com"))
        val channel = Channel(
            id = 1,
            sourceId = source,
            streamId = "c1",
            name = "Channel 1",
            categoryId = null,
            epgChannelId = null,
            logoUrl = null,
            number = 1,
            streamUrl = "https://example.com/live/c1.ts",
        )
        db.channels().upsertAll(listOf(channel))

        graph.settings.setActiveProfile(profileId)
        graph.viewerLibrary.recordProgress("movie:1", positionMillis = 60_000, durationMillis = 6_000_000)
        graph.viewerLibrary.live.record(channel)
        graph.viewerLibrary.favourites.set(channel, favourite = true)
        graph.profileSettings.setSubtitlesOn(true)
        graph.settings.setActiveProfile(Profiles.BASE_PROFILE_ID)
        db.seriesDismissals().upsert(SeriesDismissal(profileId, source, "s", 1, lastSeason = 1, lastEpisode = 1))
        db.favourites().insert(Favourite(profileId, StreamKind.MOVIE, "Provider", "m1"))
        db.profileSettings().upsert(ProfileSetting(profileId, "other", "value", 1))
        graph.recordingRepository.insert(recording(profileId))

        for (table in SEEDED_TABLES) {
            assertWithMessage("seeded $table").that(rowsFor(table, profileId)).isGreaterThan(0)
        }
    }

    private fun recording(profileId: Long) = Recording(
        channelId = 1,
        sourceId = 1,
        channelName = "Channel 1",
        title = "Recording",
        filePath = "/recordings/1.ts",
        streamUrl = "https://example.com/live/c1.ts",
        status = RecordingStatus.COMPLETED,
        profileId = profileId,
    )

    // Read from the database Room built, so a table added to the schema shows up here on its own.
    private fun tablesWithAProfileId(): Set<String> {
        val sqlite = db.openHelper.readableDatabase
        val tables = sqlite.query(
            "SELECT name FROM sqlite_master WHERE type = 'table' " +
                "AND name NOT LIKE 'sqlite_%' AND name NOT IN ('android_metadata', 'room_master_table')",
        ).use { c -> buildList { while (c.moveToNext()) add(c.getString(0)) } }
        return tables.filter { table ->
            sqlite.query("PRAGMA table_info(`$table`)").use { c ->
                val name = c.getColumnIndexOrThrow("name")
                generateSequence { if (c.moveToNext()) c.getString(name) else null }.any { it == "profileId" }
            }
        }.toSet()
    }

    private fun rowsFor(table: String, profileId: Long): Int =
        db.openHelper.readableDatabase.query("SELECT COUNT(*) FROM `$table` WHERE profileId = ?", arrayOf(profileId))
            .use { c ->
                c.moveToFirst()
                c.getInt(0)
            }

    private companion object {
        val SEEDED_TABLES = setOf(
            "playback_positions",
            "series_dismissals",
            "live_playback",
            "favourites",
            "profile_settings",
            "recordings",
        )
    }
}
