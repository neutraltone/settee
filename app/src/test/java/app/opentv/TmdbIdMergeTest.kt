/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv

import app.opentv.data.model.Movie
import app.opentv.data.model.Series
import app.opentv.data.remote.TmdbMeta
import app.opentv.data.repo.withTmdb
import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * A title TMDB matched keeps the id it was matched by: the provider's own when it had a real one,
 * else the one TMDB found, including in place of the 0 panels send for a title they haven't matched.
 */
class TmdbIdMergeTest {

    private val found = TmdbMeta(tmdbId = "11", overview = "A film.")

    private fun movie(tmdbId: String?) = Movie(
        sourceId = 1, streamId = "1", name = "A Film", categoryId = null, posterUrl = null, rating = null,
        year = null, plot = null, durationSeconds = null, containerExtension = null, streamUrl = "", tmdbId = tmdbId,
    )

    private fun series(tmdbId: String?) = Series(
        sourceId = 1, seriesId = "1", name = "A Show", categoryId = null, posterUrl = null, rating = null,
        year = null, plot = null, tmdbId = tmdbId,
    )

    @Test
    fun `a film stored with TMDB id 0 takes the id TMDB found`() {
        assertThat(movie("0").withTmdb(found, at = 1).tmdbId).isEqualTo("11")
        assertThat(movie(" 0 ").withTmdb(found, at = 1).tmdbId).isEqualTo("11")
    }

    @Test
    fun `a series stored with TMDB id 0 takes the id TMDB found`() {
        assertThat(series("0").withTmdb(found, at = 1).tmdbId).isEqualTo("11")
        assertThat(series("  ").withTmdb(found, at = 1).tmdbId).isEqualTo("11")
    }

    @Test
    fun `a title with no TMDB id takes the id TMDB found`() {
        assertThat(movie(null).withTmdb(found, at = 1).tmdbId).isEqualTo("11")
        assertThat(series(null).withTmdb(found, at = 1).tmdbId).isEqualTo("11")
    }

    @Test
    fun `a provider's real TMDB id is never replaced`() {
        assertThat(movie("603").withTmdb(found, at = 1).tmdbId).isEqualTo("603")
        assertThat(series("1399").withTmdb(found, at = 1).tmdbId).isEqualTo("1399")
    }

    @Test
    fun `no match leaves a stored 0 as it was`() {
        assertThat(movie("0").withTmdb(null, at = 1).tmdbId).isEqualTo("0")
    }
}
