/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv

import app.opentv.data.model.Movie
import app.opentv.data.repo.EpisodeStatuses
import app.opentv.data.repo.ShowEpisodes
import app.opentv.data.repo.WatchStatus
import app.opentv.data.repo.groupVersions
import app.opentv.data.repo.showEpisodes
import com.google.common.truth.Truth.assertThat
import org.junit.Test

/** A film or show shows once, however many Versions the provider lists (#181, ADR 0007). */
class TitleVersionsTest {

    @Test
    fun `two rows with the same TMDB id are one title, whatever their names`() {
        val titles = group(
            movie(1, "NF - The Matrix", tmdbId = "603"),
            movie(2, "AMZ - The Matrix (1999)", tmdbId = "603"),
            movie(3, "EN-TOP - 16. The Matrix", tmdbId = "603"),
        )

        assertThat(titles.map { versions -> versions.map { it.id } }).containsExactly(listOf(1L, 2L, 3L))
    }

    @Test
    fun `rows with no TMDB id group by cleaned name and year`() {
        val titles = group(
            movie(1, "EN - Hacker (2016)"),
            movie(2, "NF - Hacker", year = 2016, tmdbId = "0"),
            movie(3, "4K-EN - Hacker (2016)", tmdbId = ""),
            movie(4, "EN - Hacker (2010)"),
        )

        assertThat(titles.map { versions -> versions.map { it.id } })
            .containsExactly(listOf(1L, 2L, 3L), listOf(4L))
    }

    @Test
    fun `rows whose TMDB ids differ never group, even with the same name and year`() {
        val titles = group(
            movie(1, "EN - Life Itself (2018)", tmdbId = "419478"),
            movie(2, "NF - Life Itself (2018)", tmdbId = "520001"),
        )

        assertThat(titles).hasSize(2)
    }

    @Test
    fun `a row with no TMDB id joins the one title of its name and year`() {
        val titles = group(
            movie(1, "NF - Superbad (2007)", tmdbId = "8363"),
            movie(2, "EN - Superbad (2007)"),
        )

        assertThat(titles.map { versions -> versions.map { it.id } }).containsExactly(listOf(1L, 2L))
    }

    @Test
    fun `a row with no TMDB id stays apart when two titles share its name and year`() {
        val titles = group(
            movie(1, "EN - Life Itself (2018)", tmdbId = "419478"),
            movie(2, "NF - Life Itself (2018)", tmdbId = "520001"),
            movie(3, "AMZ - Life Itself (2018)"),
        )

        assertThat(titles.map { versions -> versions.map { it.id } })
            .containsExactly(listOf(1L), listOf(2L), listOf(3L))
    }

    @Test
    fun `a show's episodes are every one found in any of its Versions, matched by season and episode`() {
        val opened = listOf(episode(11, "s1", 1, 1), episode(12, "s1", 1, 2))
        val other = listOf(episode(21, "s2", 1, 1), episode(22, "s2", 1, 2), episode(23, "s2", 1, 3))

        val shown = showEpisodes(opened, listOf(other))

        assertThat(shown.map { it.id }).containsExactly(11L, 12L, 23L).inOrder()
    }

    @Test
    fun `an episode a Version lists twice stays twice`() {
        val opened = listOf(episode(11, "s1", 1, 1), episode(19, "s1", 1, 1))

        assertThat(showEpisodes(opened, listOf(listOf(episode(21, "s2", 1, 1)))).map { it.id })
            .containsExactly(11L, 19L)
    }

    @Test
    fun `a show's page shows an episode Watched in another Version as Watched, but not a repeat`() {
        val opened = listOf(episode(11, "s1", 1, 1), episode(19, "s1", 1, 1))
        val other = listOf(episode(21, "s2", 1, 1))
        val show = ShowEpisodes.of(opened, listOf(other))

        val shown = show.statusesFrom(EpisodeStatuses(mapOf(21L to WatchStatus.Watched)))
        val repeat = show.statusesFrom(EpisodeStatuses(mapOf(11L to WatchStatus.Watched)))

        assertThat(shown[11]).isEqualTo(WatchStatus.Watched)
        assertThat(repeat[19]).isEqualTo(WatchStatus.Unwatched)
    }

    private fun group(vararg movies: Movie) = groupVersions(movies.toList(), Movie::tmdbId, Movie::name, Movie::year)

    private fun episode(id: Long, seriesId: String, season: Int, number: Int) =
        testEpisode(season, number).copy(id = id, seriesId = seriesId, episodeId = "$id")

    private fun movie(id: Long, name: String, tmdbId: String? = null, year: Int? = null) = Movie(
        id = id,
        sourceId = 1,
        streamId = "$id",
        name = name,
        categoryId = null,
        posterUrl = null,
        rating = null,
        year = year,
        plot = null,
        durationSeconds = null,
        containerExtension = null,
        streamUrl = "http://example.com/$id.mp4",
        tmdbId = tmdbId,
    )
}
