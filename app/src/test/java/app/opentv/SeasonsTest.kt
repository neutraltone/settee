/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv

import app.opentv.data.repo.SeriesMainAction
import app.opentv.ui.vod.openingSeason
import app.opentv.ui.vod.seasonsOf
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class SeasonsTest {

    @Test
    fun `seasons come in order, each with its episodes in order`() {
        val seasons = seasonsOf(listOf(testEpisode(2, 2), testEpisode(1, 2), testEpisode(2, 1), testEpisode(1, 1)))

        assertThat(seasons.map { it.number }).containsExactly(1, 2).inOrder()
        assertThat(seasons[0].episodes).containsExactly(testEpisode(1, 1), testEpisode(1, 2)).inOrder()
        assertThat(seasons[1].episodes).containsExactly(testEpisode(2, 1), testEpisode(2, 2)).inOrder()
    }

    @Test
    fun `specials come after the last season`() {
        val seasons = seasonsOf(listOf(testEpisode(0, 1), testEpisode(1, 1), testEpisode(10, 1), testEpisode(2, 1)))

        assertThat(seasons.map { it.number }).containsExactly(1, 2, 10, 0).inOrder()
    }

    @Test
    fun `no episodes, no seasons`() {
        assertThat(seasonsOf(emptyList())).isEmpty()
    }

    private val twoSeasons = seasonsOf((1..2).flatMap { s -> (1..3).map { testEpisode(s, it) } })

    @Test
    fun `the page opens on the season of the episode the main button plays`() {
        assertThat(openingSeason(twoSeasons, SeriesMainAction.Resume(testEpisode(2, 2)))).isEqualTo(2)
        assertThat(openingSeason(twoSeasons, SeriesMainAction.Play(testEpisode(1, 3)))).isEqualTo(1)
    }

    @Test
    fun `with no main button yet the page opens on Season 1`() {
        assertThat(openingSeason(twoSeasons, mainAction = null)).isEqualTo(1)
    }

    @Test
    fun `with no main button and only specials the page opens on the specials`() {
        assertThat(openingSeason(seasonsOf(listOf(testEpisode(0, 1))), mainAction = null)).isEqualTo(0)
    }

    @Test
    fun `before any episodes have arrived there is no season to open on`() {
        assertThat(openingSeason(emptyList(), mainAction = null)).isNull()
    }
}
