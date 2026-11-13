/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv

import app.opentv.data.model.Episode
import app.opentv.data.model.Movie
import app.opentv.data.model.SubtitleKind
import app.opentv.data.remote.SubtitleResult
import app.opentv.data.repo.DownloadedSubtitles
import app.opentv.data.repo.SubtitleTitle
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class DownloadedSubtitlesTest {

    @get:Rule
    val folder = TemporaryFolder()

    private var now = 1_000L
    private fun store(cap: Long = 20L * 1024 * 1024) = DownloadedSubtitles(folder.root, clock = { now }, capBytes = cap)

    private val heat = SubtitleTitle.film(source = "Test", streamId = "101")
    private val srt = "1\n00:00:01,000 --> 00:00:02,000\nHello\n"

    private fun result(fileId: Long, kind: SubtitleKind = SubtitleKind.STANDARD, release: String = "r") =
        SubtitleResult(fileId, language = "en", release = release, downloads = 0, kind = kind)

    @Test
    fun `a saved subtitle is offered for its title from then on, with no new download`() = runTest {
        store().save(heat, result(11, kind = SubtitleKind.HEARING_IMPAIRED, release = "Heat.1995"), srt)

        // A new store, as after Settee restarts.
        val saved = store().forTitle(heat).single()
        assertThat(saved.language).isEqualTo("en")
        assertThat(saved.kind).isEqualTo(SubtitleKind.HEARING_IMPAIRED)
        assertThat(saved.release).isEqualTo("Heat.1995")
        assertThat(saved.file.readText()).isEqualTo(srt)
    }

    @Test
    fun `a title is matched by its source's name and the provider's id, not Settee's row id`() = runTest {
        val before = Movie(
            id = 5, sourceId = 1, streamId = "101", name = "Heat", categoryId = null, posterUrl = null,
            rating = null, year = null, plot = null, durationSeconds = null, containerExtension = null,
            streamUrl = "http://example.com/1",
        )
        // A Catalogue Refresh can give the same film a new row id.
        val after = before.copy(id = 99)
        store().save(SubtitleTitle.of(before, source = "Test"), result(11), srt)

        assertThat(store().forTitle(SubtitleTitle.of(after, source = "Test"))).hasSize(1)
        assertThat(store().forTitle(SubtitleTitle.of(after, source = "Other"))).isEmpty()
    }

    @Test
    fun `an episode is matched by its provider's episode id`() = runTest {
        val episode = Episode(
            id = 7, sourceId = 1, seriesId = "s", episodeId = "e9", season = 1, episodeNumber = 2,
            title = "Pilot", plot = null, durationSeconds = null, stillUrl = null, streamUrl = "http://example.com/e",
        )
        store().save(SubtitleTitle.of(episode, source = "Test"), result(11), srt)

        assertThat(store().forTitle(SubtitleTitle.of(episode.copy(id = 8), source = "Test"))).hasSize(1)
        assertThat(store().forTitle(SubtitleTitle.film(source = "Test", streamId = "e9"))).isEmpty()
    }

    @Test
    fun `saving the same file again replaces it rather than keeping two`() = runTest {
        store().save(heat, result(11), srt)
        store().save(heat, result(11), srt + "2\n")

        assertThat(store().forTitle(heat).single().file.readText()).endsWith("2\n")
    }

    @Test
    fun `over the cap, the subtitles unplayed for longest go first`() = runTest {
        val text = "x".repeat(40)
        val films = (1..3).map { SubtitleTitle.film("Test", "$it") }
        // Room for three 40-byte files, not four.
        val store = store(cap = 130)
        films.forEach {
            store.save(it, result(it.itemId.toLong()), text)
            now += 10
        }
        // The first one saved was played since, so the second is now the one unplayed for longest.
        store.played(store.forTitle(films[0]).single())
        now += 10

        store.save(SubtitleTitle.film("Test", "4"), result(4), text)

        assertThat(store.forTitle(films[1])).isEmpty()
        assertThat(store.forTitle(films[0])).hasSize(1)
        assertThat(store.forTitle(films[2])).hasSize(1)
        assertThat(folder.root.walk().filter { it.isFile && it.extension == "srt" }.sumOf { it.length() })
            .isAtMost(130L)
    }

    @Test
    fun `a subtitle bigger than the whole cap isn't kept, and nothing else goes to make room for it`() = runTest {
        val store = store(cap = 100)
        store.save(SubtitleTitle.film("Test", "1"), result(1), "x".repeat(40))

        val tooBig = runCatching { store.save(heat, result(11), "x".repeat(150)) }

        assertThat(tooBig.isFailure).isTrue()
        assertThat(store.forTitle(heat)).isEmpty()
        assertThat(store.forTitle(SubtitleTitle.film("Test", "1"))).hasSize(1)
    }

    @Test
    fun `a timing offset is kept with the title's file`() = runTest {
        val saved = store().save(heat, result(11), srt)

        store().setOffset(saved, offsetMillis = 300)

        assertThat(store().forTitle(heat).single().offsetMillis).isEqualTo(300)
    }
}
