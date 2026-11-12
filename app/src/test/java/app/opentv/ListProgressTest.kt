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

/**
 * How far through saving a movie or show list a fetch has got, for Setup's bar. Panels don't say
 * how big the list is, but once it's downloaded the file's size is known, and saving it took most
 * of the time on a Stick.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class ListProgressTest {

    private val catalogue = TestCatalog(ApplicationProvider.getApplicationContext())

    @After
    fun close() = catalogue.close()

    @Test
    fun `saving a movie list moves the progress as it goes, never backwards, to the end`() = runTest {
        catalogue.db.sources().insert(PANEL_SOURCE)
        catalogue.panel.unsized += "get_vod_streams"
        catalogue.panel.replies["get_vod_streams"] =
            (1..3_000).joinToString(",", "[", "]") { """{"stream_id": $it, "name": "Film $it", "genre": "Drama"}""" }
        val reported = mutableListOf<Float>()

        catalogue.catalog.fetchMovies(PANEL_SOURCE, progress = { reported += it.share })

        assertThat(reported.filter { it > 0.2f && it < 0.8f }).isNotEmpty()
        assertThat(reported).isInOrder()
        assertThat(reported.last()).isEqualTo(1f)
    }

    @Test
    fun `saving a show list moves the progress as it goes, to the end`() = runTest {
        catalogue.db.sources().insert(PANEL_SOURCE)
        catalogue.panel.replies["get_series"] =
            (1..3_000).joinToString(",", "[", "]") { """{"series_id": $it, "name": "Show $it"}""" }
        val reported = mutableListOf<Float>()

        catalogue.catalog.fetchShows(PANEL_SOURCE, progress = { reported += it.share })

        assertThat(reported.filter { it > 0.2f && it < 0.8f }).isNotEmpty()
        assertThat(reported.last()).isEqualTo(1f)
    }

    @Test
    fun `saving a movie list counts the films saved so far, as it goes`() = runTest {
        catalogue.db.sources().insert(PANEL_SOURCE)
        catalogue.panel.replies["get_vod_streams"] =
            (1..3_000).joinToString(",", "[", "]") { """{"stream_id": $it, "name": "Film $it", "genre": "Drama"}""" }
        val counts = mutableListOf<Int>()

        catalogue.catalog.fetchMovies(PANEL_SOURCE, progress = { counts += it.saved })

        assertThat(counts.filter { it in 1..2_999 }).isNotEmpty()
        assertThat(counts).isInOrder()
        assertThat(counts.last()).isEqualTo(3_000)
    }
}
