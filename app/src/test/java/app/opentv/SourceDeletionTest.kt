/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import app.opentv.data.model.Channel
import app.opentv.data.model.GuideProgramme
import app.opentv.data.model.Movie
import app.opentv.data.model.PlaybackPosition
import app.opentv.data.model.SeriesRule
import app.opentv.data.model.Source
import app.opentv.data.model.SourceKind
import app.opentv.data.repo.ReminderRepository
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.flow.first
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Deleting a source takes what was set on its channels with it, so a source added later under the
 * same name starts afresh rather than reviving them by name (#333), and takes its films' and episodes'
 * watch progress with it (#337).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class SourceDeletionTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val test = TestCatalog(context)
    private val db = test.db
    private val channels = db.channels()
    private val reminders = ReminderRepository(db.reminders(), db.sources())

    @After
    fun close() = test.close()

    private suspend fun home() =
        db.sources().insert(Source(name = "Home", kind = SourceKind.M3U, url = "https://example.com/h.m3u"))

    private suspend fun away() =
        db.sources().insert(Source(name = "Away", kind = SourceKind.M3U, url = "https://example.com/a.m3u"))

    private fun fromProvider(sourceId: Long, streamId: String) = Channel(
        sourceId = sourceId,
        streamId = streamId,
        name = "Station $streamId",
        groupKey = "station$streamId",
        streamUrl = "https://example.com/$streamId.ts",
        categoryId = null,
        logoUrl = null,
        epgChannelId = null,
        number = null,
    )

    private val tomorrow = GuideProgramme(
        epgChannelId = "b",
        startUtcMillis = 2_000_000_000_000,
        endUtcMillis = 2_000_003_600_000,
        title = "News",
    )

    @Test
    fun `a source added again under the same name has none of the deleted one's links, reminders or edits`() = testing {
        val first = home()
        channels.replaceCatalogue(first, listOf(fromProvider(first, "a"), fromProvider(first, "b")), syncStamp = 1)
        val b = channels.forSource(first).single { it.streamId == "b" }
        channels.setCustomName(b.id, "My Station")
        db.seriesRules().insert(
            SeriesRule(
                channelId = b.id,
                source = "Home",
                streamId = "b",
                channelName = "B",
                titleKey = "news",
                title = "News",
            ),
        )
        reminders.remind(b, tomorrow, autoTune = false)
        channels.replaceCatalogue(first, listOf(fromProvider(first, "a")), syncStamp = 2)

        test.catalog.deleteSource(first)
        val again = home()
        channels.replaceCatalogue(again, listOf(fromProvider(again, "b")), syncStamp = 3)

        assertThat(channels.forSource(again).single().customName).isNull()
        assertThat(db.seriesRules().observeAll().first()).isEmpty()
        assertThat(reminders.observeAll().first()).isEmpty()
    }

    @Test
    fun `deleting a source takes its guide with it and leaves the other source's alone`() = testing {
        val first = home()
        val second = away()
        test.guide.ensureFeeds()
        test.seedProviderGuide(first, "home.news")
        test.seedProviderGuide(second, "away.news")

        test.catalog.deleteSource(first)

        val feeds = test.guide.observeFeeds().first()
        assertThat(feeds.mapNotNull { it.providerSourceId }).containsExactly(second)
        assertThat(test.guide.upcoming("home.news", nowUtcMillis = 0)).isEmpty()
        assertThat(test.guide.upcoming("away.news", nowUtcMillis = 0).map { it.title }).containsExactly("News")
        assertThat(db.epgAliases().all().map { it.epgId }).containsExactly("away.news")
    }

    @Test
    fun `a guide refresh after deleting a source has no failed feeds`() = testing {
        val first = home()
        val second = away()
        test.guide.ensureFeeds()
        val now = tomorrow.startUtcMillis
        // Fetched just now, so the refresh counts the remaining provider's guide without downloading it.
        db.epgFeeds().markSynced(db.epgFeeds().forProvider(second)!!.id, now, "")

        test.catalog.deleteSource(first)

        // Gone before any refresh, which would otherwise clear it up itself.
        assertThat(db.epgFeeds().forProvider(first)).isNull()
        assertThat(test.guide.syncAll(nowUtcMillis = now).feedsFailed).isEqualTo(0)
    }

    private fun film(sourceId: Long, streamId: String) = Movie(
        sourceId = sourceId,
        streamId = streamId,
        name = "Film $streamId",
        categoryId = null,
        posterUrl = null,
        rating = null,
        year = null,
        plot = null,
        durationSeconds = null,
        containerExtension = "mp4",
        streamUrl = "https://example.com/movie/$streamId.mp4",
    )

    @Test
    fun `deleting a source takes its films' and episodes' progress and leaves the other source's alone`() = testing {
        val first = home()
        val second = away()
        val (homeFilm, awayFilm) = db.movies().insertRows(listOf(film(first, "1"), film(second, "2")))
        val homeEpisode = testEpisode(1, 1).copy(sourceId = first)
        val awayEpisode = testEpisode(1, 2).copy(sourceId = second)
        db.episodes().upsertAll(listOf(homeEpisode, awayEpisode))
        val library = test.viewerLibrary
        library.recordProgress("movie:$homeFilm", positionMillis = 600_000, durationMillis = 1_000_000)
        library.recordProgress("movie:$awayFilm", positionMillis = 600_000, durationMillis = 1_000_000)
        library.recordProgress("ep:${homeEpisode.id}", positionMillis = 600_000, durationMillis = 1_000_000)
        library.recordProgress("ep:${awayEpisode.id}", positionMillis = 600_000, durationMillis = 1_000_000)
        // Another Profile's progress on the removed source's film goes too.
        db.positions().upsert(
            PlaybackPosition(
                profileId = 2,
                mediaKey = "movie:$homeFilm",
                positionMillis = 600_000,
                durationMillis = 1_000_000,
                updatedAtMillis = 0,
            ),
        )

        test.catalog.deleteSource(first)

        assertThat(db.positions().all().map { it.profileId to it.mediaKey }).containsExactly(
            1L to "movie:$awayFilm",
            1L to "ep:${awayEpisode.id}",
        )
    }
}
