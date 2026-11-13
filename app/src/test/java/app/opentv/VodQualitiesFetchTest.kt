/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv

import android.app.Application
import androidx.media3.common.MimeTypes
import androidx.test.core.app.ApplicationProvider
import app.opentv.data.model.Series
import app.opentv.data.repo.MediaKey
import app.opentv.data.repo.ProfileSettings
import app.opentv.data.repo.VodQualities
import app.opentv.player.DecoderSize
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Another Quality of a show has usually never been opened, so its episodes aren't on the device: the
 * Plan asks the provider for them, but only when this device won't try the row opened (#180). On a real
 * clock: the fetch runs on the panel's own thread, and virtual time would run out its limit straight away.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class VodQualitiesFetchTest {

    private val test = TestCatalog(ApplicationProvider.getApplicationContext<Application>())
    private val stick = FakeDecoders(MimeTypes.VIDEO_H265 to DecoderSize(1920, 1088))
    private val profile = MutableStateFlow(1L)
    private val qualities = VodQualities(
        test.db,
        test.catalog,
        stick,
        ProfileSettings(test.db, profile),
        deviceLanguage = { "en" },
    )

    private fun series(id: Long, name: String) = Series(
        id = id,
        sourceId = PANEL_SOURCE.id,
        seriesId = "$id",
        name = name,
        categoryId = null,
        posterUrl = null,
        rating = null,
        year = null,
        plot = null,
        tmdbId = "94997",
    )

    private val copyAsked = "get_series_info:2"

    @Before
    fun catalogue() = runBlocking {
        test.db.sources().insert(PANEL_SOURCE)
        test.db.series().upsertAll(listOf(series(1, "4K-MAX - Dragons (2022)"), series(2, "MAX - Dragons (2022)")))
    }

    @After
    fun close() = test.close()

    private suspend fun openedIn(seriesId: Long, name: String): MediaKey.Episode {
        test.db.episodes().upsertAll(
            listOf(
                testEpisode(1, 2).copy(id = 0, sourceId = PANEL_SOURCE.id, seriesId = "$seriesId", episodeId = name),
            ),
        )
        return MediaKey.Episode(test.db.episodes().forSeries(PANEL_SOURCE.id, "$seriesId").single().id)
    }

    @Test
    fun `the same episode of a copy never opened is fetched and plays`() = runBlocking {
        val opened = openedIn(1, "a")
        test.panel.replies[copyAsked] = """
            {"info": {}, "episodes": {"1": [
                {"id": "b1", "season": 1, "episode_num": 1, "title": "One"},
                {"id": "b2", "season": 1, "episode_num": 2, "title": "Two"}
            ]}}
        """.trimIndent()

        val plan = qualities.planFor(opened)

        val two = test.db.episodes().forSeries(PANEL_SOURCE.id, "2").single { it.episodeId == "b2" }
        assertThat(plan.start.key).isEqualTo(MediaKey.Episode(two.id))
        assertThat(plan.skipped).isEqualTo("4K")
    }

    @Test
    fun `a provider that doesn't answer leaves the row opened to be tried`() = runBlocking {
        val opened = openedIn(1, "a")
        test.panel.replies[copyAsked] = "!"

        val plan = qualities.planFor(opened)

        assertThat(plan.start.key).isEqualTo(opened)
        assertThat(plan.skipped).isNull()
    }

    @Test
    fun `a copy with no such episode leaves the row opened to be tried`() = runBlocking {
        val opened = openedIn(1, "a")
        test.panel.replies[copyAsked] =
            """{"info": {}, "episodes": {"1": [{"id": "b1", "season": 1, "episode_num": 1, "title": "One"}]}}"""

        assertThat(qualities.planFor(opened).start.key).isEqualTo(opened)
    }

    @Test
    fun `a copy already on the device isn't fetched again`() = runBlocking {
        val opened = openedIn(1, "a")
        val copy = openedIn(2, "b")

        assertThat(qualities.planFor(opened).start.key).isEqualTo(copy)
        assertThat(test.panel.asked).doesNotContain(copyAsked)
    }

    @Test
    fun `a row this device can play starts without waiting on the provider`() = runBlocking {
        val opened = openedIn(2, "b")

        val plan = qualities.planFor(opened)

        assertThat(plan.start.key).isEqualTo(opened)
        assertThat(test.panel.asked).isEmpty()
    }
}
