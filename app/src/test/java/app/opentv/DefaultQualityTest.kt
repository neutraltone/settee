/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv

import android.content.Context
import androidx.media3.common.MimeTypes
import androidx.test.core.app.ApplicationProvider
import app.opentv.data.model.Channel
import app.opentv.data.model.Movie
import app.opentv.data.model.Source
import app.opentv.data.model.SourceKind
import app.opentv.data.repo.ChannelLineup
import app.opentv.data.repo.LineupChannel
import app.opentv.data.repo.LineupScope
import app.opentv.data.repo.collapseMovieVariants
import app.opentv.player.DecoderSize
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The Quality that plays by default is the highest this device's decoders can play, going by its name (#184). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class DefaultQualityTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val catalogue = TestCatalog(context)
    private val db = catalogue.db
    private val sharing = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @Before
    fun provider() = runTest {
        db.sources().insert(Source(id = 1, name = "Provider", kind = SourceKind.XTREAM, url = "http://example.com"))
    }

    @After
    fun close() {
        sharing.cancel()
        catalogue.close()
    }

    @Test
    fun `a Channel skips a 4K Quality on a device whose decoders stop at 1080p`() = runTest {
        val channel = channelOn(decodersUpTo(1088), "News 4K", "News HD")

        assertThat(channel.primary.name).isEqualTo("News HD")
        assertThat(channel.qualities.map { it.name }).containsExactly("News 4K", "News HD").inOrder()
    }

    @Test
    fun `a Movie group skips a 4K Quality on a device whose decoders stop at 1080p`() {
        val group = groupOf(decodersUpTo(1088), "Film 2001 4K", "Film 2001 HD")

        assertThat(group.primary.name).isEqualTo("Film 2001 HD")
        assertThat(group.variants.map { it.movie.name }).containsExactly("Film 2001 4K", "Film 2001 HD").inOrder()
    }

    @Test
    fun `4K stays the default where a decoder lists 2160`() = runTest {
        assertThat(channelOn(decodersUpTo(2160), "News 4K", "News HD").primary.name).isEqualTo("News 4K")
        assertThat(groupOf(decodersUpTo(2160), "Film 2001 4K", "Film 2001 HD").primary.name)
            .isEqualTo("Film 2001 4K")
    }

    @Test
    fun `either H_264 or HEVC listing the height counts, as the name doesn't say which a stream uses`() = runTest {
        val h264Only = FakeDecoders(
            MimeTypes.VIDEO_H264 to DecoderSize(3840, 2160),
            MimeTypes.VIDEO_H265 to DecoderSize(1920, 1088),
        )

        assertThat(channelOn(h264Only, "News 4K", "News HD").primary.name).isEqualTo("News 4K")
    }

    @Test
    fun `a decoder for another codec doesn't count`() = runTest {
        val av1Only = FakeDecoders(MimeTypes.VIDEO_AV1 to DecoderSize(3840, 2160))

        assertThat(channelOn(av1Only, "News 4K", "News HD").primary.name).isEqualTo("News HD")
    }

    @Test
    fun `an 8K Quality needs a decoder that lists 4320`() = runTest {
        assertThat(channelOn(decodersUpTo(2160), "News 8K", "News 4K").primary.name).isEqualTo("News 4K")
        assertThat(channelOn(decodersUpTo(4320), "News 8K", "News 4K").primary.name).isEqualTo("News 8K")
    }

    @Test
    fun `when every Quality is skipped the best still plays, so it gets the sentence`() = runTest {
        assertThat(channelOn(decodersUpTo(1088), "News 8K", "News 4K").primary.name).isEqualTo("News 8K")
        assertThat(groupOf(decodersUpTo(1088), "Film 2001 8K", "Film 2001 4K").primary.name)
            .isEqualTo("Film 2001 8K")
    }

    @Test
    fun `a name with no tag counts as playable`() = runTest {
        assertThat(channelOn(decodersUpTo(480), "News").primary.name).isEqualTo("News")
    }

    @Test
    fun `what's below the Quality playing is what it can step down to, once each`() = runTest {
        val channel = channelOn(decodersUpTo(1088), "News 4K", "News HD", "News HD", "News SD")

        assertThat(channel.primary.name).isEqualTo("News HD")
        assertThat(channel.qualitiesBelow(channel.primary).map { it.name }).containsExactly("News SD")
        assertThat(channel.qualitiesBelow(channel.best).map { it.name })
            .containsExactly("News HD", "News SD").inOrder()
    }

    @Test
    fun `a default Quality stays on the provider the viewer filtered to`() = runTest {
        val lineup = twoProviderNews()

        assertThat(lineup.only(LineupScope.All(sourceId = 1)).primary.name).isEqualTo("News 4K")
        assertThat(lineup.only(LineupScope.Favourites(sourceId = 1)).primary.name).isEqualTo("News 4K")
    }

    @Test
    fun `with no provider filter it can come from another provider`() = runTest {
        val lineup = twoProviderNews()

        assertThat(lineup.only(LineupScope.All()).primary.name).isEqualTo("News HD")
        assertThat(lineup.only(LineupScope.Search("4K")).primary.name).isEqualTo("News HD")
    }

    @Test
    fun `the channel manager shows the row it found`() = runTest {
        val lineup = twoProviderNews()

        assertThat(lineup.only(LineupScope.ManageSearch("4K")).primary.name).isEqualTo("News 4K")
    }

    /** News at 4K on the first provider and HD on the second, starred, on a device that stops at 1080p. */
    private suspend fun twoProviderNews(): ChannelLineup {
        db.sources().insert(Source(id = 2, name = "Other", kind = SourceKind.XTREAM, url = "http://example.org"))
        db.channels().replaceCatalogue(1, listOf(row("0", "News 4K", sourceId = 1)), syncStamp = 1)
        db.channels().replaceCatalogue(2, listOf(row("0", "News HD", sourceId = 2)), syncStamp = 1)
        catalogue.catalog.renormalizeAll()
        val lineup = lineupOn(decodersUpTo(1088))
        lineup.setFavourite(lineup.channels(LineupScope.All()).first().single(), true)
        return lineup
    }

    private suspend fun ChannelLineup.only(scope: LineupScope) = channels(scope).first().single()

    private fun row(streamId: String, name: String, sourceId: Long = 1) = Channel(
        sourceId = sourceId,
        streamId = streamId,
        name = name,
        categoryId = null,
        logoUrl = null,
        epgChannelId = null,
        number = null,
        streamUrl = "http://example.com/live/u/p/$sourceId$streamId.ts",
    )

    private fun lineupOn(decoders: FakeDecoders) = ChannelLineup(
        db,
        catalogue.viewerLibrary.favourites,
        MutableStateFlow(emptySet()),
        MutableStateFlow(false),
        decoders,
        sharing,
    )

    private suspend fun channelOn(decoders: FakeDecoders, vararg names: String): LineupChannel {
        val rows = names.mapIndexed { i, name -> row("$i", name) }
        db.channels().replaceCatalogue(1, rows, syncStamp = 1)
        catalogue.catalog.renormalizeAll()
        return lineupOn(decoders).channels(LineupScope.All()).first().single()
    }

    private fun groupOf(decoders: FakeDecoders, vararg names: String) = collapseMovieVariants(
        names.mapIndexed { i, name ->
            Movie(
                id = i + 1L,
                sourceId = 1,
                streamId = "$i",
                name = name,
                categoryId = null,
                posterUrl = null,
                rating = null,
                year = null,
                plot = null,
                durationSeconds = null,
                containerExtension = null,
                streamUrl = "http://example.com/$i.mp4",
            )
        },
        decoders,
    ).single()

    private fun decodersUpTo(height: Int) = FakeDecoders(
        MimeTypes.VIDEO_H265 to DecoderSize(height * 16 / 9, height),
    )
}
