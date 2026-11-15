/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv

import app.opentv.data.model.Channel
import app.opentv.data.model.GuideProgramme
import app.opentv.data.repo.LineupChannel
import app.opentv.ui.ChannelsViewModel
import com.google.common.truth.Truth.assertThat
import org.junit.Test

/** What a guide row says is on at a given minute, whenever the row was built. */
class GuideRowTest {
    private val channel = Channel(
        id = 1,
        sourceId = 1,
        streamId = "1",
        name = "Channel One",
        streamUrl = "https://example.com/1.ts",
        categoryId = null,
        logoUrl = null,
        epgChannelId = "epg1",
        number = 1,
    )

    private val news = GuideProgramme("epg1", startUtcMillis = 1_000, endUtcMillis = 2_000, title = "News")
    private val film = GuideProgramme("epg1", startUtcMillis = 2_000, endUtcMillis = 5_000, title = "Film")

    /** Built while the news was on, so the row's own `now` is the news. */
    private val row = ChannelsViewModel.Row(
        LineupChannel(channel, listOf(channel)),
        now = news,
        next = film,
        programmes = listOf(news, film),
    )

    @Test
    fun `once a programme has ended, the one after it is on, not the one on when the row was built`() {
        assertThat(row.liveAt(2_500)).isEqualTo(film)
    }

    @Test
    fun `a programme is on from its first minute`() {
        assertThat(row.liveAt(2_000)).isEqualTo(film)
    }

    @Test
    fun `after the row's last programme, nothing is on`() {
        assertThat(row.liveAt(5_000)).isNull()
    }

    @Test
    fun `in a gap between programmes, nothing is on`() {
        val gappy = row.copy(programmes = listOf(news, film.copy(startUtcMillis = 3_000)))

        assertThat(gappy.liveAt(2_500)).isNull()
    }
}
