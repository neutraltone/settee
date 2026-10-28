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
import app.opentv.data.model.Series
import app.opentv.data.remote.XtreamApi
import app.opentv.data.repo.LatestAirDates
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The step after a sync that finds when recently updated shows last had an episode out. */
// Plain Application: OpenTvApp schedules WorkManager on start, which the database doesn't need.
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class LatestAirDatesTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val db = Room.inMemoryDatabaseBuilder(context, OpenTvDatabase::class.java)
        .allowMainThreadQueries()
        .build()
    private val panel = FakePanel()
    private val tmdbAsked = mutableListOf<String>()
    private var tmdbLastAirDates = mapOf<String, Long>()
    private val step = LatestAirDates(db.series(), XtreamApi(panel.http)) { series ->
        tmdbAsked += series.seriesId
        tmdbLastAirDates[series.seriesId]
    }

    @After
    fun close() = db.close()

    private fun series(id: String, updated: Long, sourceId: Long = 1) = Series(
        sourceId = sourceId,
        seriesId = id,
        name = "Show $id",
        categoryId = null,
        posterUrl = null,
        rating = null,
        year = null,
        plot = null,
        addedMillis = updated,
    )

    private suspend fun latestAirDate(id: String): Long? = db.series().byProviderId(1, id)?.latestAirDate

    private fun episodeOut(date: String) =
        """{"info": {}, "episodes": {"1": [{"id": "1", "info": {"air_date": "$date"}}]}}"""

    @Test
    fun `only the 50 most recently updated shows from the synced provider are looked up`() = runTest {
        db.series().upsertAll(
            (1..52).map { series("$it", updated = it.toLong()) } + series("other", updated = 99, sourceId = 2),
        )

        step.refresh(PANEL_SOURCE)

        assertThat(panel.asked.map { it.substringAfter(':') }).containsExactlyElementsIn((3..52).map { "$it" })
    }

    @Test
    fun `a show's latest air date is its newest episode's, and its episodes are not kept`() = runTest {
        db.series().upsertAll(listOf(series("7", updated = 1)))
        panel.replies["get_series_info"] = episodeOut("2024-03-15")

        step.refresh(PANEL_SOURCE)

        assertThat(latestAirDate("7")).isEqualTo(1_710_460_800_000L)
        assertThat(db.episodes().forSeries(1, "7")).isEmpty()
        assertThat(tmdbAsked).isEmpty()
    }

    @Test
    fun `with no episode dates it falls back to the show's release date, then TMDB`() = runTest {
        db.series().upsertAll(listOf(series("released", updated = 1), series("tmdb", updated = 2)))
        panel.replies["get_series_info:released"] = """{"info": {"releaseDate": "2011-09-02"}}"""
        panel.replies["get_series_info:tmdb"] = """{"info": {}}"""
        tmdbLastAirDates = mapOf("tmdb" to 1_710_460_800_000L)

        step.refresh(PANEL_SOURCE)

        assertThat(latestAirDate("released")).isEqualTo(1_314_921_600_000L)
        assertThat(latestAirDate("tmdb")).isEqualTo(1_710_460_800_000L)
        assertThat(tmdbAsked).containsExactly("tmdb")
    }

    @Test
    fun `a show whose lookup fails is left out, and the rest are still dated`() = runTest {
        db.series().upsertAll(listOf(series("broken", updated = 2), series("fine", updated = 1)))
        panel.replies["get_series_info:broken"] = "!"
        panel.replies["get_series_info:fine"] = episodeOut("2024-03-15")
        tmdbLastAirDates = emptyMap()

        step.refresh(PANEL_SOURCE)

        assertThat(latestAirDate("broken")).isNull()
        assertThat(latestAirDate("fine")).isEqualTo(1_710_460_800_000L)
    }

    @Test
    fun `a TMDB lookup that throws costs only that show`() = runTest {
        db.series().upsertAll(listOf(series("throws", updated = 2), series("fine", updated = 1)))
        panel.replies["get_series_info:throws"] = """{"info": {}}"""
        panel.replies["get_series_info:fine"] = episodeOut("2024-03-15")
        val throwing = LatestAirDates(db.series(), XtreamApi(panel.http)) { error("TMDB is down") }

        throwing.refresh(PANEL_SOURCE)

        assertThat(latestAirDate("throws")).isNull()
        assertThat(latestAirDate("fine")).isEqualTo(1_710_460_800_000L)
    }
}
