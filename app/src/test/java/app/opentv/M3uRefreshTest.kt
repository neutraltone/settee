/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import app.opentv.data.model.Category
import app.opentv.data.model.Channel
import app.opentv.data.model.Source
import app.opentv.data.model.SourceKind
import app.opentv.data.model.StreamKind
import app.opentv.data.repo.CatalogRepository.SyncResult
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Refreshing an M3U playlist's live channels. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class M3uRefreshTest {

    private val test = TestCatalog(ApplicationProvider.getApplicationContext())
    private val playlist = Source(id = 2, name = "List", kind = SourceKind.M3U, url = "http://example.com/get.php")

    @After
    fun close() = test.close()

    @Test
    fun `films and episodes stored as channels before drop out on the next refresh`() = runTest {
        test.db.sources().insert(playlist)
        // As an earlier version stored an m3u_plus playlist: its films and episodes as channels.
        test.db.channels().replaceCatalogue(
            playlist.id,
            listOf(
                channel("tvg:bbc1", "BBC One", "http://example.com/live/u/p/1.ts", 0),
                channel("url:film", "Drive (2011)", "http://example.com/movie/u/p/30.mkv", 1),
                channel("url:episode", "Taskmaster S01 E01", "http://example.com/series/u/p/40.mp4", 2),
            ),
            syncStamp = 1,
        )
        test.panel.replies[""] = """
            #EXTM3U
            #EXTINF:-1 tvg-id="bbc1",BBC One
            http://example.com/live/u/p/1.ts
            #EXTINF:-1,Drive (2011)
            http://example.com/movie/u/p/30.mkv
            #EXTINF:-1,Taskmaster S01 E01
            http://example.com/series/u/p/40.mp4
        """.trimIndent()

        test.catalog.fetchLive(playlist, nowUtcMillis = 2)

        assertThat(test.catalog.observeChannels(playlist.id).first().map { it.name })
            .containsExactly("BBC One")
    }

    @Test
    fun `film and show groups stored as live categories before leave the channel list`() = runTest {
        test.db.sources().insert(playlist)
        // As an earlier version stored them: a live category for every group-title, films' too.
        test.db.categories().upsertAll(
            listOf(
                Category(id = "UK", sourceId = playlist.id, name = "UK", kind = StreamKind.LIVE, sortIndex = 0),
                Category(id = "VOD | Action", sourceId = playlist.id, name = "VOD | Action", kind = StreamKind.LIVE),
                Category(
                    id = "SERIES | Comedy",
                    sourceId = playlist.id,
                    name = "SERIES | Comedy",
                    kind = StreamKind.LIVE,
                ),
            ),
        )
        test.db.channels().replaceCatalogue(
            playlist.id,
            listOf(
                channel("tvg:bbc1", "BBC One", "http://example.com/live/u/p/1.ts", 0, category = "UK"),
                channel(
                    "url:film",
                    "Drive (2011)",
                    "http://example.com/movie/u/p/30.mkv",
                    1,
                    category = "VOD | Action",
                ),
                channel(
                    "url:ep",
                    "Taskmaster S01 E01",
                    "http://example.com/series/u/p/40.mp4",
                    2,
                    category = "SERIES | Comedy",
                ),
            ),
            syncStamp = 1,
        )
        test.panel.replies[""] = """
            #EXTM3U
            #EXTINF:-1 tvg-id="bbc1" group-title="UK",BBC One
            http://example.com/live/u/p/1.ts
            #EXTINF:-1 group-title="VOD | Action",Drive (2011)
            http://example.com/movie/u/p/30.mkv
            #EXTINF:-1 group-title="SERIES | Comedy",Taskmaster S01 E01
            http://example.com/series/u/p/40.mp4
        """.trimIndent()

        test.catalog.fetchLive(playlist, nowUtcMillis = 2)

        assertThat(test.lineup.categoryGroups(playlist.id).first().flatMap { it.ids }).containsExactly("UK")
    }

    @Test
    fun `a playlist that comes back with only films and episodes keeps your channels as they were`() = runTest {
        test.db.sources().insert(playlist)
        test.db.categories().upsertAll(
            listOf(Category(id = "UK", sourceId = playlist.id, name = "UK", kind = StreamKind.LIVE, sortIndex = 0)),
        )
        test.db.channels().replaceCatalogue(
            playlist.id,
            listOf(
                channel("tvg:bbc1", "BBC One", "http://example.com/live/u/p/1.ts", 0, category = "UK"),
                channel("tvg:itv1", "ITV 1", "http://example.com/live/u/p/2.ts", 1, category = "UK"),
                channel("tvg:ch4", "Channel 4", "http://example.com/live/u/p/3.ts", 2, category = "UK"),
            ),
            syncStamp = 1,
        )
        val stored = test.catalog.observeChannelsInIncludingHidden(playlist.id, listOf("UK")).first()
        val byStream = stored.associateBy { it.streamId }
        test.catalog.setChannelHidden(byStream.getValue("tvg:itv1").id, hidden = true, now = 1)
        test.catalog.setChannelOrder(listOf("tvg:ch4", "tvg:bbc1", "tvg:itv1").map { byStream.getValue(it).id })
        test.catalog.setChannelCustomName(byStream.getValue("tvg:bbc1").id, "Beeb")
        test.guide.setManualOverride(byStream.getValue("tvg:ch4").id, "channel4.uk")
        test.panel.replies[""] = """
            #EXTM3U
            #EXTINF:-1 group-title="VOD | Action",Drive (2011)
            http://example.com/movie/u/p/30.mkv
            #EXTINF:-1 group-title="SERIES | Comedy",Taskmaster S01 E01
            http://example.com/series/u/p/40.mp4
        """.trimIndent()

        val result = test.catalog.fetchLive(playlist, nowUtcMillis = 2)

        assertThat((result as SyncResult.Failed).reason.sentence).isEqualTo(
            ApplicationProvider.getApplicationContext<Context>().getString(R.string.error_playlist_empty),
        )
        val after = test.catalog.observeChannelsInIncludingHidden(playlist.id, listOf("UK")).first()
        assertThat(after.map { Edits(it.streamId, it.hidden, it.sortIndex, it.customName, it.epgOverrideId) })
            .containsExactly(
                Edits("tvg:ch4", hidden = false, sortIndex = 0, customName = null, epgOverride = "channel4.uk"),
                Edits("tvg:bbc1", hidden = false, sortIndex = 1, customName = "Beeb", epgOverride = null),
                Edits("tvg:itv1", hidden = true, sortIndex = 2, customName = null, epgOverride = null),
            )
        assertThat(test.lineup.categoryGroups(playlist.id).first().flatMap { it.ids }).containsExactly("UK")
    }

    /** What a viewer can change about a channel, which a refresh must carry over. */
    private data class Edits(
        val streamId: String,
        val hidden: Boolean,
        val sortIndex: Int,
        val customName: String?,
        val epgOverride: String?,
    )

    private fun channel(streamId: String, name: String, url: String, sortIndex: Int, category: String? = null) =
        Channel(
            sourceId = playlist.id,
            streamId = streamId,
            name = name,
            categoryId = category,
            logoUrl = null,
            epgChannelId = null,
            number = null,
            streamUrl = url,
            sortIndex = sortIndex,
        )
}
