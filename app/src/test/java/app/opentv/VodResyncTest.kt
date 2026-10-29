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

/** A movies-and-shows refresh picks up what the provider changed without losing what was filled in since. */
// Plain Application: OpenTvApp schedules WorkManager on start, which the database doesn't need.
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class VodResyncTest {

    private val test = TestCatalog(ApplicationProvider.getApplicationContext())
    private val db = test.db
    private val panel = test.panel

    @After
    fun close() = test.close()

    @Test
    fun `a show the provider updated moves up Recently Added, keeping its place in the library`() = runTest {
        panel.replies["get_series"] = """[{"series_id": 7, "name": "Show", "last_modified": "100"}]"""
        test.syncVod()
        val before = db.series().byProviderId(1, "7")!!

        panel.replies["get_series"] = """[{"series_id": 7, "name": "Show", "last_modified": "200"}]"""
        test.syncVod()

        val after = db.series().byProviderId(1, "7")!!
        assertThat(after.addedMillis).isEqualTo(200_000L)
        assertThat(after.id).isEqualTo(before.id)
    }

    @Test
    fun `a refresh keeps the release date and artwork filled in when the movie was opened`() = runTest {
        panel.replies["get_vod_streams"] = """[{"stream_id": 3, "name": "Drive", "added": "100"}]"""
        panel.replies["get_vod_info"] =
            """{"info": {"releasedate": "2011-09-02", "backdrop_path": ["https://example.com/b.jpg"]}}"""
        test.syncVod()
        test.catalog.movieDetail(db.movies().all().single().id)

        panel.replies["get_vod_streams"] = """[{"stream_id": 3, "name": "Drive", "added": "300"}]"""
        test.syncVod()

        val movie = db.movies().all().single()
        assertThat(movie.addedMillis).isEqualTo(300_000L)
        assertThat(movie.releaseDate).isEqualTo(1_314_921_600_000L)
        assertThat(movie.backdropUrl).isEqualTo("https://example.com/b.jpg")
    }
}
