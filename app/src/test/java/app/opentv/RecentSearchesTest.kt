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
import app.opentv.data.repo.ViewerLibrary
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
class RecentSearchesTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val db = Room.inMemoryDatabaseBuilder(context, OpenTvDatabase::class.java)
        .allowMainThreadQueries()
        .build()
    private val activeProfile = MutableStateFlow(1L)
    private var now = 1_000L
    private val library = ViewerLibrary(db, activeProfile, clock = { now++ })
    private val recent = library.recentSearches

    @After
    fun close() = db.close()

    private suspend fun shown() = recent.observe().first()

    @Test
    fun `newest search comes first`() = runTest {
        recent.save("bbc")
        recent.save("news")

        assertThat(shown()).containsExactly("news", "bbc").inOrder()
    }

    @Test
    fun `a Profile keeps its last ten`() = runTest {
        (1..12).forEach { recent.save("search $it") }

        assertThat(shown()).containsExactlyElementsIn((12 downTo 3).map { "search $it" }).inOrder()
    }

    @Test
    fun `repeating a search moves it to the top without duplicating`() = runTest {
        recent.save("bbc")
        recent.save("news")

        recent.save("bbc")

        assertThat(shown()).containsExactly("bbc", "news").inOrder()
    }

    @Test
    fun `a search differing only in case or surrounding spaces is the same one, in its latest spelling`() = runTest {
        recent.save("bbc")
        recent.save("news")

        recent.save("  BBC ")

        assertThat(shown()).containsExactly("BBC", "news").inOrder()
    }

    @Test
    fun `a blank search saves nothing`() = runTest {
        recent.save("   ")

        assertThat(shown()).isEmpty()
    }

    @Test
    fun `each Profile has its own`() = runTest {
        recent.save("bbc")

        activeProfile.value = 2
        assertThat(shown()).isEmpty()
        recent.save("cartoons")
        assertThat(shown()).containsExactly("cartoons")

        activeProfile.value = 1
        assertThat(shown()).containsExactly("bbc")
    }

    @Test
    fun `the cap counts one Profile's searches, not everyone's`() = runTest {
        (1..10).forEach { recent.save("search $it") }

        activeProfile.value = 2
        recent.save("cartoons")

        activeProfile.value = 1
        assertThat(shown()).hasSize(10)
    }

    @Test
    fun `removing one leaves the rest`() = runTest {
        recent.save("bbc")
        recent.save("news")

        recent.remove("bbc")

        assertThat(shown()).containsExactly("news")
    }

    @Test
    fun `clearing removes only the active Profile's`() = runTest {
        recent.save("bbc")
        activeProfile.value = 2
        recent.save("cartoons")

        recent.clear()

        assertThat(shown()).isEmpty()
        activeProfile.value = 1
        assertThat(shown()).containsExactly("bbc")
    }

    @Test
    fun `forgetting a Profile forgets its searches`() = runTest {
        recent.save("bbc")
        activeProfile.value = 2
        recent.save("cartoons")

        library.profileRemoval.forget(2)

        assertThat(shown()).isEmpty()
        activeProfile.value = 1
        assertThat(shown()).containsExactly("bbc")
    }
}
