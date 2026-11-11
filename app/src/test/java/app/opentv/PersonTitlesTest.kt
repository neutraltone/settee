/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv

import androidx.test.core.app.ApplicationProvider
import app.opentv.data.repo.PersonTitle
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * What a person's page lists: every film and show in the library with them in it, as an actor or
 * the director, including films nobody has opened yet.
 */
// Plain Application: OpenTvApp schedules WorkManager on start, which the database doesn't need.
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class PersonTitlesTest {

    private val test = TestCatalog(ApplicationProvider.getApplicationContext())
    private val panel = test.panel

    @After
    fun close() = test.close()

    private suspend fun titlesWith(name: String): List<String> = test.catalog.titlesWithPerson(name).map {
        when (it) {
            is PersonTitle.MovieItem -> it.movie.name
            is PersonTitle.SeriesItem -> it.series.name
        }
    }

    @Test
    fun `a film nobody has opened is on its actor's and director's pages after a sync`() = runTest {
        panel.replies["get_vod_streams"] = """[{"stream_id": 3, "name": "Drive"}]"""
        panel.replies["get_vod_info"] =
            """{"info": {"genre": "Crime", "cast": "Ryan Gosling, Carey Mulligan", "director": "Nicolas Winding Refn"}}"""

        test.syncVod()

        assertThat(titlesWith("Carey Mulligan")).containsExactly("Drive")
        assertThat(titlesWith("Nicolas Winding Refn")).containsExactly("Drive")
    }
}
