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

/** How opening a movie fills in its release date for Recently Released. */
// Plain Application: OpenTvApp schedules WorkManager on start, which the database doesn't need.
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class MovieDetailDatesTest {

    private val test = TestCatalog(ApplicationProvider.getApplicationContext())

    @After
    fun close() = test.close()

    @Test
    fun `opening a movie the provider can't date asks for its date once, not on every open`() = runTest {
        // Already has its artwork and cast from the list, so only the missing date sends it to the provider.
        test.panel.replies["get_vod_streams"] = """
            [{"stream_id": 3, "name": "Drive", "cast": "Ryan Gosling", "backdrop_path": "https://example.com/b.jpg"}]
        """
        test.panel.replies["get_vod_info"] = """{"info": {"year": "2011"}}"""
        test.syncVod()
        val id = test.db.movies().all().single().id

        repeat(3) { test.catalog.movieDetail(id) }

        assertThat(test.panel.asked.count { it == "get_vod_info" }).isEqualTo(1)
    }
}
