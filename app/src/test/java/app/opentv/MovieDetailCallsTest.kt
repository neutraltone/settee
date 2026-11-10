/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv

import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** What opening a movie asks the provider for. */
// Plain Application: OpenTvApp schedules WorkManager on start, which the database doesn't need.
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class MovieDetailCallsTest {

    private val test = TestCatalog(ApplicationProvider.getApplicationContext())

    @After
    fun close() = test.close()

    @Test
    fun `opening a movie the list already filled in asks the provider nothing, even with no release date`() = runTest {
        test.panel.replies["get_vod_streams"] = """
                [{"stream_id": 3, "name": "Drive", "cast": "Ryan Gosling", "backdrop_path": "https://example.com/b.jpg"}]
            """
        test.panel.replies["get_vod_info"] = """{"info": {"releasedate": "2011-09-02"}}"""
        test.syncVod()
        test.panel.asked.clear()
        val id = test.db.movies().all().single().id

        test.catalog.movieDetail(id)

        assertThat(test.panel.asked).doesNotContain("get_vod_info:3")
    }
}
