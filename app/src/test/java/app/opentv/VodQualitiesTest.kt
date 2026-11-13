/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv

import android.app.Application
import androidx.media3.common.MimeTypes
import androidx.test.core.app.ApplicationProvider
import app.opentv.core.ServiceLocator
import app.opentv.data.model.Movie
import app.opentv.data.model.Series
import app.opentv.data.model.Source
import app.opentv.data.model.SourceKind
import app.opentv.data.repo.MediaKey
import app.opentv.data.repo.VodQualities
import app.opentv.player.DecoderList
import app.opentv.player.DecoderSize
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Names are the ones a real provider ships: every Quality of a title is its own row, in its own category,
 * under its own prefix, and only their TMDB id says they're the same title (#180).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class VodQualitiesTest {

    private val app: Application = ApplicationProvider.getApplicationContext()
    private val graph = ServiceLocator.Graph(app)
    private val db = graph.database
    private val stick = FakeDecoders(MimeTypes.VIDEO_H265 to DecoderSize(1920, 1088))
    private val fourK = FakeDecoders(MimeTypes.VIDEO_H265 to DecoderSize(3840, 2160))

    @Before
    fun provider() = runTest {
        db.sources().insert(Source(id = 1, name = "Provider", kind = SourceKind.XTREAM, url = "http://example.com"))
    }

    @After
    fun close() = db.close()

    private fun qualities(decoders: DecoderList = stick) = VodQualities(
        db,
        graph.catalogRepository,
        decoders,
        graph.profileSettings,
        graph.settings.activeProfileId,
        deviceLanguage = { "en" },
    )

    private fun movie(id: Long, name: String, tmdbId: String? = "8363") = Movie(
        id = id,
        sourceId = 1,
        streamId = "$id",
        name = name,
        categoryId = null,
        posterUrl = null,
        rating = null,
        year = null,
        plot = null,
        durationSeconds = null,
        containerExtension = "mkv",
        streamUrl = "https://example.com/$id",
        tmdbId = tmdbId,
    )

    private fun series(id: Long, name: String) = Series(
        id = id,
        sourceId = 1,
        seriesId = "s$id",
        name = name,
        categoryId = null,
        posterUrl = null,
        rating = null,
        year = null,
        plot = null,
        tmdbId = "94997",
    )

    private val top4k = movie(1, "4K-TOP - Superbad (2007)")
    private val en4k = movie(2, "4K-EN - Superbad  (2007)")
    private val en = movie(3, "EN - Superbad  (2007)")

    @Test
    fun `a 4K row this device can't play opens on the copy it can, without trying either 4K row`() = runTest {
        db.movies().upsertAll(listOf(top4k, en4k, en))

        val plan = qualities().planFor(MediaKey.Movie(1))

        assertThat(plan.start.key).isEqualTo(MediaKey.Movie(3))
        assertThat(plan.lower).isEmpty()
        assertThat(plan.skipped).isEqualTo("4K")
    }

    @Test
    fun `the copy on the same shelf comes first, whatever its id`() = runTest {
        val top = movie(4, "TOP - Superbad (2007)")
        val other = movie(3, "AS - Superbad (2007)")
        db.movies().upsertAll(listOf(movie(1, "4K-TOP - Superbad (2007)"), other, top))

        val plan = qualities().planFor(MediaKey.Movie(1))

        assertThat(plan.start.key).isEqualTo(MediaKey.Movie(4))
        assertThat(plan.lower.map { it.key }).containsExactly(MediaKey.Movie(3))
    }

    @Test
    fun `a tagged Quality starts before an untagged one, which says nothing of its size`() = runTest {
        db.movies().upsertAll(listOf(en, movie(4, "EN - Superbad HD (2007)")))

        val plan = qualities().planFor(MediaKey.Movie(3))

        assertThat(plan.start.key).isEqualTo(MediaKey.Movie(4))
        assertThat(plan.lower.map { it.key }).containsExactly(MediaKey.Movie(3))
    }

    @Test
    fun `a device that plays 4K plays the row opened, with the rest to step down to`() = runTest {
        db.movies().upsertAll(listOf(top4k, en4k, en))

        val plan = qualities(fourK).planFor(MediaKey.Movie(1))

        assertThat(plan.start.key).isEqualTo(MediaKey.Movie(1))
        assertThat(plan.lower.map { it.key }).containsExactly(MediaKey.Movie(2), MediaKey.Movie(3)).inOrder()
        assertThat(plan.skipped).isNull()
    }

    @Test
    fun `a row this device can play stays the one that plays`() = runTest {
        db.movies().upsertAll(listOf(top4k, en))

        val plan = qualities().planFor(MediaKey.Movie(3))

        assertThat(plan.start.key).isEqualTo(MediaKey.Movie(3))
        assertThat(plan.lower).isEmpty()
        assertThat(plan.skipped).isNull()
    }

    @Test
    fun `a 4K row with no other copy is tried, for the sentence to say what happened`() = runTest {
        db.movies().upsertAll(listOf(top4k))

        val plan = qualities().planFor(MediaKey.Movie(1))

        assertThat(plan.start.key).isEqualTo(MediaKey.Movie(1))
        assertThat(plan.skipped).isNull()
    }

    @Test
    fun `titles a panel knows no TMDB id for aren't copies of each other`() = runTest {
        db.movies().upsertAll(
            listOf(movie(1, "4K-EN - One", "0"), movie(2, "EN - Other", "0"), movie(3, "EN - Third", null)),
        )

        assertThat(qualities().planFor(MediaKey.Movie(1)).start.key).isEqualTo(MediaKey.Movie(1))
    }

    @Test
    fun `a 4K show's episode opens on the same episode of the copy this device can play`() = runTest {
        db.series().upsertAll(
            listOf(
                series(1, "4K-MAX - House of the Dragon (2022) (US)"),
                series(2, "MAX - House of the Dragon (2022) (US)"),
            ),
        )
        db.episodes().upsertAll(
            listOf(
                testEpisode(1, 1).copy(id = 11, seriesId = "s1", episodeId = "a"),
                testEpisode(1, 1).copy(id = 21, seriesId = "s2", episodeId = "b"),
                testEpisode(1, 2).copy(id = 22, seriesId = "s2", episodeId = "c"),
            ),
        )

        val plan = qualities().planFor(MediaKey.Episode(11))

        assertThat(plan.start.key).isEqualTo(MediaKey.Episode(21))
        assertThat(plan.skipped).isEqualTo("4K")
    }

    @Test
    fun `an episode the copy doesn't have is tried in the row opened`() = runTest {
        db.series().upsertAll(
            listOf(
                series(1, "4K-MAX - House of the Dragon (2022) (US)"),
                series(2, "MAX - House of the Dragon (2022) (US)"),
            ),
        )
        db.episodes().upsertAll(listOf(testEpisode(1, 1).copy(id = 11, seriesId = "s1", episodeId = "a")))

        val plan = qualities().planFor(MediaKey.Episode(11))

        assertThat(plan.start.key).isEqualTo(MediaKey.Episode(11))
        assertThat(plan.skipped).isNull()
    }

    @Test
    fun `the best Quality this device can play starts, whichever Version was opened`() = runTest {
        db.movies().upsertAll(listOf(top4k, en, movie(4, "NF - Superbad FHD (2007)")))

        val plan = qualities().planFor(MediaKey.Movie(3))

        assertThat(plan.start.key).isEqualTo(MediaKey.Movie(4))
        assertThat(plan.lower.map { it.key }).containsExactly(MediaKey.Movie(3))
        assertThat(plan.skipped).isNull()
    }

    @Test
    fun `a cam copy comes last, and never starts while another Version plays`() = runTest {
        val cam = movie(5, "EN - Superbad (2007) HDCAM")
        db.movies().upsertAll(listOf(cam, en))

        val plan = qualities().planFor(MediaKey.Movie(5))

        assertThat(plan.start.key).isEqualTo(MediaKey.Movie(3))
        assertThat(plan.lower.map { it.key }).containsExactly(MediaKey.Movie(5))
    }

    @Test
    fun `a cam copy starts when it's the only one that plays`() = runTest {
        db.movies().upsertAll(listOf(top4k, movie(5, "EN - Superbad (2007) HDCAM")))

        assertThat(qualities().planFor(MediaKey.Movie(1)).start.key).isEqualTo(MediaKey.Movie(5))
    }

    @Test
    fun `what it steps down to stays in the device's language`() = runTest {
        db.movies().upsertAll(listOf(en4k, en, movie(6, "DE - Superbad HD (2007)")))

        val plan = qualities().planFor(MediaKey.Movie(2))

        assertThat(plan.start.key).isEqualTo(MediaKey.Movie(3))
        assertThat(plan.lower).isEmpty()
        assertThat(plan.otherLanguages).isEmpty()
    }

    @Test
    fun `when only another language plays, the plan asks rather than starting it`() = runTest {
        db.movies().upsertAll(listOf(en4k, movie(6, "DE - Superbad HD (2007)")))

        val plan = qualities().planFor(MediaKey.Movie(2))

        assertThat(plan.start.key).isEqualTo(MediaKey.Movie(2))
        assertThat(plan.otherLanguages.map { it.key }).containsExactly(MediaKey.Movie(6))
    }

    @Test
    fun `the Version this Profile last played starts`() = runTest {
        db.movies().upsertAll(listOf(en, movie(4, "NF - Superbad FHD (2007)")))
        graph.viewerLibrary.recordProgress(MediaKey.Movie(3).stored, positionMillis = 60_000, durationMillis = 6_000_000)

        assertThat(qualities().planFor(MediaKey.Movie(4)).start.key).isEqualTo(MediaKey.Movie(3))
    }

    @Test
    fun `a Version chosen after the last one played starts`() = runTest {
        db.movies().upsertAll(listOf(en, movie(4, "NF - Superbad FHD (2007)"), movie(7, "AMZ - Superbad (2007)")))
        graph.viewerLibrary.recordProgress(MediaKey.Movie(3).stored, positionMillis = 60_000, durationMillis = 6_000_000)

        qualities().choose(MediaKey.Movie(7))

        assertThat(qualities().planFor(MediaKey.Movie(4)).start.key).isEqualTo(MediaKey.Movie(7))
    }

    @Test
    fun `a Version chosen in another language starts without asking again`() = runTest {
        db.movies().upsertAll(listOf(en4k, movie(6, "DE - Superbad HD (2007)")))

        qualities().choose(MediaKey.Movie(6))
        val plan = qualities().planFor(MediaKey.Movie(2))

        assertThat(plan.start.key).isEqualTo(MediaKey.Movie(6))
        assertThat(plan.otherLanguages).isEmpty()
    }

    @Test
    fun `a chosen Version this device can't play is passed over`() = runTest {
        db.movies().upsertAll(listOf(en4k, en))

        qualities().choose(MediaKey.Movie(2))

        assertThat(qualities().planFor(MediaKey.Movie(3)).start.key).isEqualTo(MediaKey.Movie(3))
    }
}
