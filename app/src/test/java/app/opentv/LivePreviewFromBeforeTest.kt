/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv

import android.content.Context
import androidx.core.content.edit
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import app.opentv.core.AppSettings
import app.opentv.core.carryLivePreviewFromBefore
import app.opentv.data.db.OpenTvDatabase
import app.opentv.data.model.Profile
import app.opentv.data.model.ProfileSetting
import app.opentv.data.repo.HomeArrangement
import app.opentv.data.repo.HomeRowKind
import app.opentv.data.repo.ProfileSettings
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 1.0 had one switch for the whole device, "Live preview on Home". It became each Profile's, on the
 * Home rows page (#212): a device where it was off keeps it off for its Profiles.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class LivePreviewFromBeforeTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val db = Room.inMemoryDatabaseBuilder(context, OpenTvDatabase::class.java)
        .allowMainThreadQueries()
        .build()
    private val activeProfile = MutableStateFlow(1L)
    private val profileSettings = ProfileSettings(db, activeProfile, clock = { 5_000 })
    private val settings = AppSettings.separate(context, PREFS)

    @After
    fun close() {
        db.close()
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit { clear() }
    }

    /** The switch as 1.0 stored it. */
    private fun oneZeroSwitch(on: Boolean) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit { putBoolean("live_preview_on_home", on) }

    private suspend fun homeOf(profileId: Long): HomeArrangement {
        activeProfile.value = profileId
        return profileSettings.homeArrangement.first()
    }

    private suspend fun twoProfiles() {
        db.profiles().insert(Profile(id = 1, name = "Alex"))
        db.profiles().insert(Profile(id = 2, name = "Sam"))
    }

    @Test
    fun `a device that had the Live Preview off turns it off for each Profile that hadn't arranged Home`() = runTest {
        twoProfiles()
        val alexs = HomeArrangement(HomeRowKind.entries.reversed(), hidden = emptySet(), livePreview = true)
        profileSettings.arrangeHome(alexs)
        oneZeroSwitch(on = false)

        carryLivePreviewFromBefore(settings, profileSettings)

        assertThat(homeOf(1)).isEqualTo(alexs)
        assertThat(homeOf(2)).isEqualTo(HomeArrangement.USUAL.copy(livePreview = false))
    }

    @Test
    fun `it's carried over once, so a Profile can turn the Live Preview back on`() = runTest {
        twoProfiles()
        oneZeroSwitch(on = false)
        carryLivePreviewFromBefore(settings, profileSettings)
        activeProfile.value = 2
        profileSettings.arrangeHome(HomeArrangement.USUAL)

        carryLivePreviewFromBefore(settings, profileSettings)

        assertThat(homeOf(2).livePreview).isTrue()
    }

    @Test
    fun `a device that had it on, or never touched it, writes nothing`() = runTest {
        twoProfiles()
        oneZeroSwitch(on = true)

        carryLivePreviewFromBefore(settings, profileSettings)

        assertThat(db.profileSettings().all()).isEmpty()
    }

    @Test
    fun `an arrangement chosen on any other device wins over the one carried over`() = runTest {
        twoProfiles()
        oneZeroSwitch(on = false)
        carryLivePreviewFromBefore(settings, profileSettings)

        // The oldest possible choice, as Sam's Home on another device made at any time is newer.
        val taken = profileSettings.import(
            listOf(app.opentv.sync.SyncProfileSetting("Sam", "home.rows", "preview=on;cw", updatedAtMillis = 1)),
        )

        assertThat(taken).isEqualTo(1)
        assertThat(homeOf(2).livePreview).isTrue()
        assertThat(db.profileSettings().all().map(ProfileSetting::profileId)).containsExactly(1L, 2L)
    }

    private companion object {
        const val PREFS = "live_preview_from_before_test"
    }
}
