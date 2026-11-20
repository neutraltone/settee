/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv

import app.opentv.data.model.Channel
import app.opentv.data.model.Movie
import app.opentv.data.model.Series
import app.opentv.data.repo.HomeArrangement
import app.opentv.data.repo.HomeRowKind
import app.opentv.data.repo.LineupChannel
import app.opentv.data.repo.LivePreview
import app.opentv.data.repo.MediaKey
import app.opentv.data.repo.VodTitle
import app.opentv.ui.Cover
import app.opentv.ui.HomeChannel
import app.opentv.ui.HomeSnapshots
import app.opentv.ui.HomeState
import app.opentv.ui.LiveNow
import app.opentv.ui.ResumeItem
import app.opentv.ui.SeasonEpisode
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class HomeSnapshotsTest {

    @get:Rule
    val folder = TemporaryFolder()

    private var now = 1_000_000L
    private fun store(schema: Int = 22) = HomeSnapshots(folder.root, schemaVersion = schema, clock = { now })

    private val heat = Movie(
        id = 5, sourceId = 1, streamId = "101", name = "Heat", categoryId = null,
        posterUrl = "https://example.com/heat.jpg", rating = null, year = 1995, plot = null,
        durationSeconds = null, containerExtension = null, streamUrl = "",
    )
    private val severance = Series(
        id = 7, sourceId = 1, seriesId = "202", name = "Severance", categoryId = null,
        posterUrl = "https://example.com/severance.jpg", rating = null, year = 2022, plot = null,
    )

    private val bbcOne = Channel(
        id = 3, sourceId = 1, streamId = "9", name = "UK| BBC One FHD", displayName = "BBC One",
        groupKey = "uk:bbc1", qualityRank = 3, qualityLabel = "FHD", categoryId = null,
        logoUrl = "https://example.com/bbc1.png", epgChannelId = null, number = 101, streamUrl = "",
    )

    private val home = HomeState(
        continueWatching = listOf(
            ResumeItem(
                key = MediaKey.Movie(5),
                title = "Heat",
                posterUrl = "https://example.com/heat.jpg",
                progress = 0.4f,
                titleId = 5,
                titleKey = "movie:1:101",
            ),
            ResumeItem(
                key = MediaKey.Episode(31), title = "Severance", posterUrl = "https://example.com/severance.jpg",
                progress = null, episode = SeasonEpisode(2, 3), newEpisode = true, name = "EN| Severance",
                titleId = 7, titleKey = "series:1:202",
            ),
        ),
        favouriteShows = listOf(Cover(VodTitle.OfSeries(severance))),
        favouriteMovies = emptyList(),
        favouriteChannels = listOf(
            HomeChannel(LineupChannel(bbcOne, listOf(bbcOne), favourite = true), nowOn = "News"),
        ),
        recentShows = emptyList(),
        recentMovies = listOf(Cover(VodTitle.OfMovie(heat))),
        live = LiveNow(LivePreview.Playable(bbcOne), nowTitle = "News", nextTitle = "Weather"),
        // Rearranged, so it's never drawn once in the usual order and then moved.
        arrangement = HomeArrangement.USUAL.moved(from = 4, to = 0).withShown(HomeRowKind.RECENT_SHOWS, false),
    )

    @Test
    fun `a Profile's Home comes back as it was saved, after Settee restarts`() = runTest {
        store().save(profileId = 1, home)

        // A new store, as after a cold start.
        // The Live Preview comes back as a still tile: Home plays nothing until the fresh rows come.
        assertThat(store().read(profileId = 1)).isEqualTo(home.copy(liveStill = true))
    }

    @Test
    fun `a Live Preview whose channel had gone comes back as gone`() = runTest {
        val gone = LiveNow(LivePreview.Gone("BBC Four", logoUrl = null), nowTitle = null, nextTitle = null)
        store().save(profileId = 1, home.copy(live = gone))

        assertThat(store().read(profileId = 1)!!.live).isEqualTo(gone)
    }

    @Test
    fun `another Profile never draws one Profile's Home`() = runTest {
        store().save(profileId = 1, home)

        assertThat(store().read(profileId = 2)).isNull()
    }

    @Test
    fun `a Home saved more than a week ago isn't drawn`() = runTest {
        store().save(profileId = 1, home)

        now += 7L * 24 * 60 * 60 * 1000 - 1
        assertThat(store().read(profileId = 1)).isNotNull()
        now += 2
        assertThat(store().read(profileId = 1)).isNull()
    }

    @Test
    fun `a Home saved before the database changed shape isn't drawn`() = runTest {
        store(schema = 22).save(profileId = 1, home)

        assertThat(store(schema = 23).read(profileId = 1)).isNull()
    }

    @Test
    fun `no stream URL is kept, as it can hold the provider's login`() = runTest {
        val playable = heat.copy(streamUrl = "http://example.com/movie/u/p/101.mkv")
        store().save(profileId = 1, home.copy(recentMovies = listOf(Cover(VodTitle.OfMovie(playable)))))

        val kept = folder.root.walk().filter { it.isFile }.map { it.readText() }.toList()
        assertThat(kept).isNotEmpty()
        assertThat(kept.none { "/u/p/" in it }).isTrue()
        // And what's read back has none to play: anything that plays looks the title up again.
        val cover = store().read(profileId = 1)!!.recentMovies.single().title as VodTitle.OfMovie
        assertThat(cover.movie.streamUrl).isEmpty()
    }
}
