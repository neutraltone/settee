/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import app.opentv.SyncDevice.Companion.EPISODE_MILLIS
import app.opentv.SyncDevice.Companion.MOVIE_MILLIS
import app.opentv.data.repo.Profiles
import app.opentv.sync.SyncFavourite
import app.opentv.sync.SyncProfileSetting
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * What happens to a change on one device when others sync (ADR 0005): removing a Profile and unlocking
 * stay put; renaming, locking, unstarring and unhiding spread; old NAS files undo none of it.
 */
// Plain Application: OpenTvApp schedules WorkManager on start, which sync doesn't need.
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class SyncSpreadsChangesTest {

    private val app: Application = ApplicationProvider.getApplicationContext()
    private val deviceA = SyncDevice(app, rowIdOffset = 0)
    private val deviceB = SyncDevice(app, rowIdOffset = 100)
    private val deviceC = SyncDevice(app, rowIdOffset = 200)

    @After
    fun close() {
        runBlocking { deviceB.awaitEpisodes() }
        deviceA.db.close()
        deviceB.db.close()
        deviceC.db.close()
    }

    /** Sends A's state to B the way the transports do: gathered, as JSON, then applied. */
    private suspend fun syncAToB() = deviceB.sync.apply(bundleFromA())

    private suspend fun bundleFromA() = deviceB.sync.decode(deviceA.sync.encode(deviceA.sync.gather()))

    @Test
    fun `a Profile removed here doesn't come back from another device or an old NAS file`() = runTest {
        deviceA.seed("Tom")
        deviceB.seed("Tom")
        deviceA.favourite("Tom", 1)
        val oldFile = bundleFromA()
        syncAToB()

        deviceB.graph.profiles.remove(deviceB.profileId("Tom"))
        syncAToB()
        deviceB.sync.apply(oldFile)

        assertThat(deviceB.hasProfile("Tom")).isFalse()
        assertThat(deviceB.db.favourites().all()).isEmpty()
        assertThat(deviceA.hasProfile("Tom")).isTrue()
        assertThat(deviceA.favouriteNames("Tom")).containsExactly("Channel 1", "Movie 1", "Series 1")
    }

    @Test
    fun `adding a removed name back brings its history from the other device`() = runTest {
        deviceA.seed("Tom")
        deviceB.seed("Tom")
        deviceA.favourite("Tom", 1)
        deviceB.graph.profiles.remove(deviceB.profileId("Tom"))

        deviceB.graph.profiles.create("Tom")
        syncAToB()

        assertThat(deviceB.favouriteNames("Tom")).containsExactly("Channel 1", "Movie 1", "Series 1")
    }

    @Test
    fun `a new Profile of a removed name on another device doesn't lift the removal here`() = runTest {
        deviceB.seed("Tom")
        deviceB.graph.profiles.remove(deviceB.profileId("Tom"))

        deviceA.seed()
        deviceA.graph.profiles.create("Tom")
        syncAToB()

        assertThat(deviceB.hasProfile("Tom")).isFalse()
    }

    @Test
    fun `a Profile removed while a sync is applying is left with no rows under its id`() = runBlocking {
        deviceA.seed("Tom")
        deviceB.seed("Tom")
        val tom = deviceB.profileId("Tom")
        val bundle = bundleFromA().copy(
            favourites = List(RACE_ITEMS) { SyncFavourite("Tom", "MOVIE", "Provider", "race$it") },
            profileSettings = List(RACE_ITEMS) { SyncProfileSetting("Tom", "setting$it", "value", 1) },
        )

        val applying = launch(Dispatchers.IO) { deviceB.sync.apply(bundle) }
        while (deviceB.db.favourites().all().isEmpty()) delay(1)
        deviceB.graph.profiles.remove(tom)
        applying.join()

        assertThat(deviceB.db.favourites().all().filter { it.profileId == tom }).isEmpty()
        assertThat(deviceB.db.profileSettings().all().filter { it.profileId == tom }).isEmpty()
    }

    @Test
    fun `a Profile removed while its episodes are being fetched is left with no rows under its id`() = runTest {
        deviceA.seed("Tom")
        deviceB.openedSeries = false
        deviceB.seed("Tom")
        deviceB.panel.replies["get_series_info:s"] = episodesReply("1-1")
        val hold = deviceB.panel.hold("get_series_info:s")
        deviceA.play("Tom", deviceA.episodeKey, 900_000, EPISODE_MILLIS)
        val tom = deviceB.profileId("Tom")

        syncAToB()
        during(hold) { deviceB.graph.profiles.remove(tom) }
        hold.release()
        deviceB.awaitEpisodes()

        assertThat(deviceB.db.positions().all().filter { it.profileId == tom }).isEmpty()
    }

    @Test
    fun `a lock spreads to the other device`() = runTest {
        deviceA.seed()
        deviceB.seed()
        deviceA.graph.settings.setCategoryLocked("adult", locked = true, now = 1_000)

        syncAToB()

        assertThat(deviceB.graph.settings.hiddenCategories.value).containsExactly("adult")
    }

    @Test
    fun `an unlock stays on its device and a sync doesn't undo it`() = runTest {
        deviceA.seed()
        deviceB.seed()
        deviceA.graph.settings.setCategoryLocked("adult", locked = true, now = 1_000)
        val oldFile = bundleFromA()
        syncAToB()

        deviceB.graph.settings.setCategoryLocked("adult", locked = false, now = 2_000)
        syncAToB()
        deviceB.sync.apply(oldFile)
        deviceA.sync.apply(deviceA.sync.decode(deviceB.sync.encode(deviceB.sync.gather())))

        assertThat(deviceB.graph.settings.hiddenCategories.value).isEmpty()
        assertThat(deviceA.graph.settings.hiddenCategories.value).containsExactly("adult")
    }

    @Test
    fun `a newer lock from another device applies after an unlock`() = runTest {
        deviceA.seed()
        deviceB.seed()
        deviceB.graph.settings.setCategoryLocked("adult", locked = true, now = 1_000)
        deviceB.graph.settings.setCategoryLocked("adult", locked = false, now = 2_000)

        deviceA.graph.settings.setCategoryLocked("adult", locked = true, now = 3_000)
        syncAToB()

        assertThat(deviceB.graph.settings.hiddenCategories.value).containsExactly("adult")
    }

    @Test
    fun `a lock passed on by a third device keeps the time it was made`() = runTest {
        deviceA.seed()
        deviceB.seed()
        deviceC.seed()
        deviceC.graph.settings.setCategoryLocked("adult", locked = false, now = 2_000)
        deviceA.graph.settings.setCategoryLocked("adult", locked = true, now = 1_000)
        syncAToB()

        deviceC.sync.apply(deviceC.sync.decode(deviceB.sync.encode(deviceB.sync.gather())))

        assertThat(deviceC.graph.settings.hiddenCategories.value).isEmpty()
    }

    @Test
    fun `a lock from an old bundle applies only where this device has never changed it`() = runTest {
        deviceB.seed()
        deviceB.graph.settings.setCategoryLocked("unlocked", locked = false, now = 2_000)

        deviceB.sync.apply(deviceB.sync.decode("""{"version": 6, "hiddenCategories": ["unlocked", "adult"]}"""))

        assertThat(deviceB.graph.settings.hiddenCategories.value).containsExactly("adult")
    }

    @Test
    fun `unstarring a Favourite spreads to the other device`() = runTest {
        deviceA.seed("Tom")
        deviceB.seed("Tom")
        deviceA.now = 1_000
        deviceA.favourite("Tom", 1)
        syncAToB()

        deviceA.now = 2_000
        deviceA.unfavourite("Tom", 1)
        syncAToB()

        assertThat(deviceB.favouriteNames("Tom")).containsExactly("Channel 1", "Series 1")
    }

    @Test
    fun `a Favourite starred again after it was unstarred elsewhere stays, whichever way it syncs`() = runTest {
        deviceA.seed("Tom")
        deviceB.seed("Tom")
        deviceA.now = 1_000
        deviceA.favourite("Tom", 1)
        syncAToB()
        deviceA.now = 2_000
        deviceA.unfavourite("Tom", 1)
        val unstarred = bundleFromA()

        deviceB.now = 3_000
        deviceB.favourite("Tom", 1)
        deviceB.sync.apply(unstarred)
        deviceA.sync.apply(deviceA.sync.decode(deviceB.sync.encode(deviceB.sync.gather())))

        assertThat(deviceB.favouriteNames("Tom")).contains("Movie 1")
        assertThat(deviceA.favouriteNames("Tom")).contains("Movie 1")
    }

    @Test
    fun `an old NAS file doesn't bring back an unstarred Favourite, however long ago it was`() = runTest {
        deviceA.seed("Tom")
        deviceB.seed("Tom")
        deviceB.now = 1_000
        deviceB.favourite("Tom", 1)
        val oldFile = deviceB.sync.decode(deviceB.sync.encode(deviceB.sync.gather()))
        deviceB.now = 2_000
        deviceB.unfavourite("Tom", 1)

        deviceB.now = 2_000 + 10L * 365 * 24 * 60 * 60 * 1_000
        deviceB.sync.apply(oldFile)

        assertThat(deviceB.favouriteNames("Tom")).containsExactly("Channel 1", "Series 1")
    }

    @Test
    fun `a Favourite with no time applies only where this device has never changed it`() = runTest {
        deviceB.seed("Tom")
        deviceB.now = 1_000
        deviceB.favourite("Tom", 1)
        deviceB.unfavourite("Tom", 1)

        deviceB.sync.apply(
            deviceB.sync.decode(
                """{"version": 6, "favourites": [
                    {"profile": "Tom", "kind": "MOVIE", "source": "Provider", "id": "m1"},
                    {"profile": "Tom", "kind": "MOVIE", "source": "Provider", "id": "m2"}
                ]}""",
            ),
        )

        assertThat(deviceB.favouriteNames("Tom")).containsExactly("Channel 1", "Series 1", "Movie 2")
    }

    @Test
    fun `unhiding a channel spreads, and an old NAS file doesn't hide it again`() = runTest {
        deviceA.seed()
        deviceB.seed()
        deviceA.now = 1_000
        deviceA.hide(1)
        val oldFile = bundleFromA()
        syncAToB()
        assertThat(deviceB.isHidden(1)).isTrue()

        deviceA.now = 2_000
        deviceA.show(1)
        syncAToB()
        assertThat(deviceB.isHidden(1)).isFalse()

        deviceB.sync.apply(oldFile)
        assertThat(deviceB.isHidden(1)).isFalse()
    }

    @Test
    fun `the newer of two changes to a channel wins, and a tie keeps what's here`() = runTest {
        deviceA.seed()
        deviceB.seed()
        deviceA.now = 2_000
        deviceA.hide(1)
        deviceA.hide(2)
        deviceB.now = 3_000
        deviceB.show(1)
        deviceB.now = 2_000
        deviceB.show(2)

        syncAToB()

        assertThat(deviceB.isHidden(1)).isFalse()
        assertThat(deviceB.isHidden(2)).isFalse()
    }

    @Test
    fun `a hidden channel with no time applies only where this device has never changed it`() = runTest {
        deviceB.seed()
        deviceB.now = 1_000
        deviceB.hide(1)
        deviceB.show(1)

        deviceB.sync.apply(
            deviceB.sync.decode(
                """{"version": 6, "hiddenStreams": [
                    {"source": "Provider", "id": "c1"},
                    {"source": "Provider", "id": "c2"}
                ]}""",
            ),
        )

        assertThat(deviceB.isHidden(1)).isFalse()
        assertThat(deviceB.isHidden(2)).isTrue()
    }

    @Test
    fun `a rename spreads, taking the Profile's history with it`() = runTest {
        deviceA.now = 1_000
        deviceB.now = 1_000
        deviceA.seed("Tom")
        deviceB.seed("Tom")
        deviceB.favourite("Tom", 1)
        val tom = deviceB.profileId("Tom")

        deviceA.now = 2_000
        deviceA.rename("Tom", "Thomas")
        syncAToB()

        assertThat(deviceB.profileNames()).containsExactly("Me", "Thomas")
        assertThat(deviceB.profileId("Thomas")).isEqualTo(tom)
        assertThat(deviceB.favouriteNames("Thomas")).containsExactly("Channel 1", "Movie 1", "Series 1")
        syncAToB()
        assertThat(deviceA.profileNames()).containsExactly("Me", "Thomas")
    }

    @Test
    fun `a rename reaches a third device through the second`() = runTest {
        listOf(deviceA, deviceB, deviceC).forEach {
            it.now = 1_000
            it.seed("Tom")
        }
        deviceA.now = 2_000
        deviceA.rename("Tom", "Thomas")

        deviceB.syncFrom(deviceA)
        deviceC.syncFrom(deviceB)

        assertThat(deviceC.profileNames()).containsExactly("Me", "Thomas")
    }

    @Test
    fun `a rename onto a name this device has merges the two, newest wins`() = runTest {
        deviceA.now = 1_000
        deviceB.now = 1_000
        deviceA.seed("Tom")
        deviceB.seed("Tom", "Thomas")
        deviceB.now = 2_000
        deviceB.favourite("Tom", 1)
        deviceB.play("Tom", deviceB.movieKey, 100_000, MOVIE_MILLIS)
        deviceB.now = 3_000
        deviceB.favourite("Thomas", 2)
        deviceB.play("Thomas", deviceB.movieKey, 200_000, MOVIE_MILLIS)

        deviceA.now = 4_000
        deviceA.rename("Tom", "Thomas")
        syncAToB()

        assertThat(deviceB.profileNames()).containsExactly("Me", "Thomas")
        assertThat(deviceB.favouriteNames("Thomas"))
            .containsExactly("Channel 1", "Channel 2", "Movie 1", "Movie 2", "Series 1", "Series 2")
        assertThat(deviceB.resumeFrom("Thomas", deviceB.movieKey)).isEqualTo(200_000)
    }

    @Test
    fun `anything still labelled with the old name lands on the new one`() = runTest {
        deviceA.now = 1_000
        deviceB.now = 1_000
        deviceA.seed("Tom")
        deviceB.seed("Tom")
        deviceA.favourite("Tom", 1)
        deviceA.play("Tom", deviceA.movieKey, 100_000, MOVIE_MILLIS)
        val oldFile = bundleFromA()
        deviceA.now = 2_000
        deviceA.rename("Tom", "Thomas")
        val renamed = bundleFromA().copy(favourites = emptyList(), progress = emptyList())
        deviceB.sync.apply(renamed)

        deviceB.sync.apply(oldFile)

        assertThat(deviceB.profileNames()).containsExactly("Me", "Thomas")
        assertThat(deviceB.favouriteNames("Thomas")).contains("Movie 1")
        assertThat(deviceB.resumeFrom("Thomas", deviceB.movieKey)).isEqualTo(100_000)
    }

    @Test
    fun `renames in a chain apply in time order`() = runTest {
        deviceA.now = 1_000
        deviceB.now = 1_000
        deviceA.seed("Tom")
        deviceB.seed("Tom")
        deviceA.now = 2_000
        deviceA.rename("Tom", "Thomas")
        deviceA.now = 3_000
        deviceA.rename("Thomas", "Tommy")

        syncAToB()

        assertThat(deviceB.profileNames()).containsExactly("Me", "Tommy")
    }

    @Test
    fun `a rename and back ends on the first name, and stays there on every later sync`() = runTest {
        deviceA.now = 1_000
        deviceB.now = 1_000
        deviceA.seed("Tom")
        deviceB.seed("Tom")
        deviceB.favourite("Tom", 1)
        val tom = deviceB.profileId("Tom")
        deviceA.now = 2_000
        deviceA.rename("Tom", "Thomas")
        deviceA.now = 3_000
        deviceA.rename("Thomas", "Tom")

        syncAToB()
        syncAToB()

        assertThat(deviceB.profileNames()).containsExactly("Me", "Tom")
        assertThat(deviceB.profileId("Tom")).isEqualTo(tom)
    }

    @Test
    fun `a rename that only changes case never merges a Profile into itself`() = runTest {
        deviceA.now = 1_000
        deviceB.now = 1_000
        deviceA.seed("tom")
        deviceB.seed("tom")
        deviceB.favourite("tom", 1)
        deviceA.now = 2_000
        deviceA.rename("tom", "Tom")

        syncAToB()

        assertThat(deviceB.profileNames()).containsExactly("Me", "Tom")
        assertThat(deviceB.favouriteNames("Tom")).containsExactly("Channel 1", "Movie 1", "Series 1")
    }

    @Test
    fun `a rename seen again changes nothing, even after this device adds the old name`() = runTest {
        deviceA.now = 1_000
        deviceB.now = 1_000
        deviceA.seed("Tom")
        deviceB.seed("Tom")
        deviceA.now = 2_000
        deviceA.rename("Tom", "Thomas")
        syncAToB()

        deviceB.now = 3_000
        checkNotNull(deviceB.graph.profiles.create("Tom"))
        val newTom = deviceB.profileId("Tom")
        syncAToB()

        assertThat(deviceB.profileNames()).containsExactly("Me", "Thomas", "Tom")
        assertThat(deviceB.profileId("Tom")).isEqualTo(newTom)
    }

    @Test
    fun `a new Profile with a renamed Profile's old name keeps what's dated after it, on every device`() = runTest {
        deviceA.now = 1_000
        deviceB.now = 1_000
        deviceA.seed("Tom")
        deviceB.seed("Tom")
        deviceA.favourite("Tom", 1)
        val oldFile = bundleFromA()
        deviceB.favourite("Tom", 2)
        deviceA.now = 2_000
        deviceA.rename("Tom", "Thomas")
        deviceA.now = 3_000
        checkNotNull(deviceA.graph.profiles.create("Tom"))
        deviceA.now = 4_000
        deviceA.play("Tom", deviceA.movieKey, 300_000, MOVIE_MILLIS)

        syncAToB()
        deviceB.sync.apply(oldFile)

        assertThat(deviceB.profileNames()).containsExactly("Me", "Thomas", "Tom")
        assertThat(deviceB.favouriteNames("Thomas"))
            .containsExactly("Channel 1", "Channel 2", "Movie 1", "Movie 2", "Series 1", "Series 2")
        assertThat(deviceB.favouriteNames("Tom")).isEmpty()
        assertThat(deviceB.resumeFrom("Tom", deviceB.movieKey)).isEqualTo(300_000)
    }

    @Test
    fun `a rename of a Profile removed here puts the new name on the removed list`() = runTest {
        deviceA.now = 1_000
        deviceB.now = 1_000
        deviceA.seed("Tom")
        deviceB.seed("Tom")
        deviceB.graph.profiles.remove(deviceB.profileId("Tom"))

        deviceA.now = 2_000
        deviceA.rename("Tom", "Thomas")
        deviceA.favourite("Thomas", 1)
        syncAToB()

        assertThat(deviceB.profileNames()).containsExactly("Me")
        assertThat(deviceB.db.favourites().all()).isEmpty()
    }

    @Test
    fun `a rename onto a name removed here happens, and the name comes off the list`() = runTest {
        deviceA.now = 1_000
        deviceB.now = 1_000
        deviceA.seed("Tom")
        deviceB.seed("Tom", "Thomas")
        deviceB.graph.profiles.remove(deviceB.profileId("Thomas"))
        val tom = deviceB.profileId("Tom")

        deviceA.now = 2_000
        deviceA.rename("Tom", "Thomas")
        syncAToB()

        assertThat(deviceB.profileNames()).containsExactly("Me", "Thomas")
        assertThat(deviceB.profileId("Thomas")).isEqualTo(tom)
    }

    @Test
    fun `a merge keeps the base Profile, whichever name it ends up with, and moves the active Profile`() = runTest {
        deviceA.now = 1_000
        deviceB.now = 1_000
        deviceA.seed()
        deviceB.seed("Mum")
        deviceB.favourite("Mum", 1)
        deviceB.asProfile("Mum")

        deviceA.now = 2_000
        deviceA.rename("Me", "Mum")
        syncAToB()

        assertThat(deviceB.graph.profiles.all().map { it.id to it.name })
            .containsExactly(Profiles.BASE_PROFILE_ID to "Mum")
        assertThat(deviceB.graph.settings.activeProfileId.value).isEqualTo(Profiles.BASE_PROFILE_ID)
        assertThat(deviceB.favouriteNames("Mum")).containsExactly("Channel 1", "Movie 1", "Series 1")
    }

    private companion object {
        const val RACE_ITEMS = 3_000
    }
}
