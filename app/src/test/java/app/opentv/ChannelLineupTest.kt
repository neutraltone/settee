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
import app.opentv.data.model.Programme
import app.opentv.data.model.Source
import app.opentv.data.model.SourceKind
import app.opentv.data.model.StreamKind
import app.opentv.data.repo.ChannelLineup
import app.opentv.data.repo.LineupChannel
import app.opentv.data.repo.LineupScope
import app.opentv.data.repo.migratedLockKeys
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

// Plain Application: OpenTvApp schedules WorkManager on start, which the database doesn't need.
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class ChannelLineupTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val catalogue = TestCatalog(context)
    private val db = catalogue.db
    private val lockedKeys = MutableStateFlow(emptySet<String>())
    private val unlocked = MutableStateFlow(false)
    private val sharing = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val lineup = ChannelLineup(db, catalogue.viewerLibrary.favourites, lockedKeys, unlocked, sharing)

    @Before
    fun twoProviders() = runTest {
        db.sources().insert(Source(id = 1, name = "Provider A", kind = SourceKind.XTREAM, url = "http://example.com"))
        db.sources().insert(Source(id = 2, name = "Provider B", kind = SourceKind.XTREAM, url = "http://example.org"))
    }

    @After
    fun close() {
        sharing.cancel()
        catalogue.close()
    }

    /** Stores [names] as one provider's rows, cleaned the way an import cleans them. */
    private suspend fun seed(vararg names: String, sourceId: Long = 1, categoryId: String? = null) {
        val rows = names.mapIndexed { i, name ->
            Channel(
                sourceId = sourceId,
                streamId = "$sourceId-$i-$name",
                name = name,
                categoryId = categoryId,
                logoUrl = null,
                epgChannelId = null,
                number = null,
                streamUrl = "http://example.com/live/u/p/$sourceId$i.ts",
            )
        }
        db.channels().replaceCatalogue(sourceId, rows, syncStamp = 1)
        catalogue.catalog.renormalizeAll()
    }

    private suspend fun category(sourceId: Long, id: String, name: String) =
        db.categories().upsertAll(listOf(Category(id = id, sourceId = sourceId, name = name, kind = StreamKind.LIVE)))

    private suspend fun row(name: String): Channel = db.channels().allForMatching().single { it.name == name }

    private suspend fun all(): List<LineupChannel> = lineup.channels(LineupScope.All()).first()

    private suspend fun scope(scope: LineupScope): List<LineupChannel> = lineup.channels(scope).first()

    private fun List<LineupChannel>.names(): List<List<String>> = map { channel -> channel.qualities.map { it.name } }

    @Test
    fun `rows of one name and region are one channel, and another region is another`() = runTest {
        seed("UK| ITV HD", "UK| ITV FHD", "US| ITV")

        assertThat(all().names()).containsExactly(listOf("UK| ITV FHD", "UK| ITV HD"), listOf("US| ITV"))
    }

    @Test
    fun `a row with no region joins the one regional channel of its name`() = runTest {
        seed("UK| BBC One HD", "BBC One")

        assertThat(all().names()).containsExactly(listOf("UK| BBC One HD", "BBC One"))
    }

    @Test
    fun `a row with no region stays on its own when its name has several regions`() = runTest {
        seed("UK| ITV", "US| ITV", "ITV")

        assertThat(all().names()).containsExactly(listOf("UK| ITV"), listOf("US| ITV"), listOf("ITV"))
    }

    @Test
    fun `a channel has the same qualities in every scope it shows in`() = runTest {
        category(sourceId = 1, id = "10", name = "UK| News")
        category(sourceId = 2, id = "20", name = "UK| Entertainment")
        seed("UK| ITV HD", sourceId = 1, categoryId = "10")
        seed("UK| ITV FHD", sourceId = 2, categoryId = "20")
        val every = listOf("UK| ITV FHD", "UK| ITV HD")

        assertThat(scope(LineupScope.Group("news")).names()).containsExactly(every)
        assertThat(scope(LineupScope.All(sourceId = 1)).names()).containsExactly(every)
        assertThat(scope(LineupScope.Search("itv")).names()).containsExactly(every)
        assertThat(lineup.channelOf(row("UK| ITV HD").id)?.qualities?.map { it.name }).isEqualTo(every)
    }

    @Test
    fun `a channel plays the quality its scope came from first`() = runTest {
        seed("UK| ITV HD", sourceId = 1)
        seed("UK| ITV FHD", sourceId = 2)

        assertThat(scope(LineupScope.All(sourceId = 1)).single().primary.name).isEqualTo("UK| ITV HD")
        assertThat(all().single().primary.name).isEqualTo("UK| ITV FHD")
    }

    @Test
    fun `a locked category leaves every scope a viewer browses, and every channel's qualities`() = runTest {
        category(sourceId = 1, id = "10", name = "UK| News")
        category(sourceId = 2, id = "66", name = "UK| Adult")
        seed("UK| ITV HD", sourceId = 1, categoryId = "10")
        seed("UK| ITV FHD", "UK| Late Night", sourceId = 2, categoryId = "66")
        lockedKeys.value = setOf("adult")

        assertThat(all().names()).containsExactly(listOf("UK| ITV HD"))
        assertThat(scope(LineupScope.Search("night"))).isEmpty()
        assertThat(scope(LineupScope.Group("adult"))).isEmpty()
        assertThat(lineup.channelOf(row("UK| ITV HD").id)?.qualities?.map { it.name }).containsExactly("UK| ITV HD")
        assertThat(lineup.channelOf(row("UK| Late Night").id)).isNull()
        assertThat(lineup.categoryGroups().first().map { it.key }).containsExactly("news")
    }

    @Test
    fun `locking a category locks it in every region`() = runTest {
        category(sourceId = 1, id = "66", name = "UK| Adult")
        category(sourceId = 2, id = "67", name = "US| ADULT")
        seed("UK| Late Night", sourceId = 1, categoryId = "66")
        seed("US| After Dark", sourceId = 2, categoryId = "67")
        lockedKeys.value = setOf("adult")

        assertThat(all()).isEmpty()
    }

    @Test
    fun `a category with no name to clean is locked on its own provider only`() = runTest {
        category(sourceId = 1, id = "7", name = "★★★")
        category(sourceId = 2, id = "7", name = "★★★")
        seed("UK| Late Night", sourceId = 1, categoryId = "7")
        seed("UK| Daytime", sourceId = 2, categoryId = "7")
        lockedKeys.value = setOf(lineup.categoryGroups(sourceId = 1).first().single().key)

        assertThat(all().names()).containsExactly(listOf("UK| Daytime"))
    }

    @Test
    fun `unlocking shows locked categories until the lock is back`() = runTest {
        category(sourceId = 1, id = "66", name = "UK| Adult")
        seed("UK| Late Night", sourceId = 1, categoryId = "66")
        lockedKeys.value = setOf("adult")
        unlocked.value = true

        assertThat(all().names()).containsExactly(listOf("UK| Late Night"))
        assertThat(lineup.categoryGroups().first().map { it.key }).containsExactly("adult")
    }

    @Test
    fun `the management scopes show locked and hidden channels so they can be managed`() = runTest {
        category(sourceId = 1, id = "66", name = "UK| Adult")
        seed("UK| Late Night HD", "UK| Late Night", sourceId = 1, categoryId = "66")
        db.channels().setHidden(row("UK| Late Night").id, true)
        lockedKeys.value = setOf("adult")

        val every = listOf("UK| Late Night HD", "UK| Late Night")
        assertThat(scope(LineupScope.ManageSearch("night")).names()).containsExactly(every)
        assertThat(scope(LineupScope.ManageGroup("adult")).names()).containsExactly(every)
        assertThat(lineup.categoryGroups(includeLocked = true).first().map { it.key }).containsExactly("adult")
    }

    @Test
    fun `starring a channel stars every quality, and its star is the same in every scope`() = runTest {
        category(sourceId = 1, id = "10", name = "UK| News")
        seed("UK| ITV HD", sourceId = 1, categoryId = "10")
        seed("UK| ITV FHD", sourceId = 2)

        lineup.setFavourite(scope(LineupScope.Group("news")).single(), favourite = true)

        val favourites = catalogue.viewerLibrary.favourites
        assertThat(favourites.contains(StreamKind.LIVE, 1, row("UK| ITV HD").streamId)).isTrue()
        assertThat(favourites.contains(StreamKind.LIVE, 2, row("UK| ITV FHD").streamId)).isTrue()
        for (where in listOf(LineupScope.All(), LineupScope.All(sourceId = 2), LineupScope.Search("itv"))) {
            assertThat(scope(where).single().favourite).isTrue()
        }
    }

    @Test
    fun `a channel is a favourite when any quality is, and shows once among the favourites`() = runTest {
        seed("UK| ITV HD", "UK| BBC One", sourceId = 1)
        seed("UK| ITV FHD", sourceId = 2)
        catalogue.viewerLibrary.favourites.set(row("UK| ITV HD"), true)

        assertThat(scope(LineupScope.Favourites()).names()).containsExactly(listOf("UK| ITV FHD", "UK| ITV HD"))
        assertThat(all().filter { it.favourite }.names()).containsExactly(listOf("UK| ITV FHD", "UK| ITV HD"))
    }

    @Test
    fun `unstarring a channel unstars every quality`() = runTest {
        seed("UK| ITV HD", sourceId = 1)
        seed("UK| ITV FHD", sourceId = 2)
        catalogue.viewerLibrary.favourites.set(row("UK| ITV HD"), true)
        catalogue.viewerLibrary.favourites.set(row("UK| ITV FHD"), true)

        lineup.setFavourite(all().single(), favourite = false)

        assertThat(scope(LineupScope.Favourites())).isEmpty()
        assertThat(catalogue.viewerLibrary.favourites.channelIds().first()).isEmpty()
    }

    private suspend fun guide(rowName: String, epgId: String) = db.channels().setEpgOverride(row(rowName).id, epgId)

    private suspend fun programmesOn(epgId: String) = db.programmes().upsertAll(
        listOf(Programme(feedId = 1, epgChannelId = epgId, startUtcMillis = 0, endUtcMillis = 60_000, title = "News")),
    )

    @Test
    fun `a channel's guide is the first guide id with programmes, its primary quality's first`() = runTest {
        seed("UK| ITV FHD", "UK| ITV HD", "UK| ITV SD")
        guide("UK| ITV FHD", "itv.empty")
        guide("UK| ITV HD", "itv.hd")
        guide("UK| ITV SD", "itv.sd")
        programmesOn("itv.hd")
        programmesOn("itv.sd")
        val itv = all().single()

        assertThat(lineup.guideId(itv)).isEqualTo("itv.hd")
        assertThat(itv.guideId { it == "itv.sd" }).isEqualTo("itv.sd")
        programmesOn("itv.empty")
        assertThat(lineup.guideId(itv)).isEqualTo("itv.empty")
    }

    @Test
    fun `a channel with no guide programmes has no guide id`() = runTest {
        seed("UK| ITV HD")
        guide("UK| ITV HD", "itv.hd")

        assertThat(lineup.guideId(all().single())).isNull()
    }

    private suspend fun zap(fromRow: String, delta: Int): String? = lineup.zap(row(fromRow).id, delta)?.primary?.name

    @Test
    fun `channel up and down follow the channels you started from`() = runTest {
        category(sourceId = 1, id = "10", name = "UK| News")
        seed("UK| BBC News", "UK| Sky News", sourceId = 1, categoryId = "10")
        seed("UK| Dave", sourceId = 1)

        lineup.zapFrom(LineupScope.Group("news"))

        assertThat(zap("UK| BBC News", +1)).isEqualTo("UK| Sky News")
        assertThat(zap("UK| Sky News", +1)).isNull()
        assertThat(zap("UK| Sky News", -1)).isEqualTo("UK| BBC News")
    }

    @Test
    fun `zapping from a quality that isn't the primary moves on from its own channel`() = runTest {
        seed("UK| BBC News", "UK| ITV FHD", "UK| ITV HD", "UK| Sky News")

        lineup.zapFrom(LineupScope.All())

        assertThat(zap("UK| ITV HD", +1)).isEqualTo("UK| Sky News")
        assertThat(zap("UK| ITV HD", -1)).isEqualTo("UK| BBC News")
    }

    @Test
    fun `zapping passes over locked categories`() = runTest {
        category(sourceId = 1, id = "66", name = "UK| Adult")
        seed("UK| BBC News", "UK| ITV", sourceId = 1)
        seed("UK| Late Night", sourceId = 2, categoryId = "66")
        category(sourceId = 2, id = "66", name = "UK| Adult")
        lockedKeys.value = setOf("adult")

        lineup.zapFrom(LineupScope.All())

        assertThat(lineup.zapChannels().map { it.primary.name }).containsExactly("UK| BBC News", "UK| ITV").inOrder()
    }

    @Test
    fun `zapping from a channel outside the list you started from goes through every channel`() = runTest {
        category(sourceId = 1, id = "10", name = "UK| News")
        seed("UK| BBC News", sourceId = 1, categoryId = "10")
        seed("UK| Dave", "UK| ITV", sourceId = 2)
        lineup.zapFrom(LineupScope.Group("news"))

        assertThat(zap("UK| Dave", +1)).isEqualTo("UK| ITV")
        assertThat(lineup.zapChannels().map { it.primary.name }).containsExactly("UK| BBC News", "UK| Dave", "UK| ITV")
    }

    @Test
    fun `a hidden row still counts when deciding which channel a row joins, so the manager agrees with the guide`() =
        runTest {
            seed("UK| ITV", "US| ITV", "ITV")
            db.channels().setHidden(row("US| ITV").id, true)

            assertThat(all().names()).containsExactly(listOf("UK| ITV"), listOf("ITV"))
            assertThat(scope(LineupScope.ManageSearch("itv")).names())
                .containsExactly(listOf("UK| ITV"), listOf("US| ITV"), listOf("ITV"))
        }

    @Test
    fun `a lock saved under a nameless category's old key carries over to its new one`() {
        val categories = listOf(
            Category(id = "7", sourceId = 1, name = "★★★", kind = StreamKind.LIVE),
            Category(id = "7", sourceId = 2, name = "★★★", kind = StreamKind.LIVE),
            Category(id = "8", sourceId = 1, name = "UK| Adult", kind = StreamKind.LIVE),
        )

        assertThat(migratedLockKeys(setOf("7", "adult"), categories)).containsExactly("1:7", "2:7", "adult")
    }

    @Test
    fun `a 40k-row catalogue folds within a time budget`() = runTest {
        val names = (0 until 20_000).flatMap { i -> listOf("UK| Station$i HD", "UK| Station$i FHD") }
        seed(*names.toTypedArray())

        // Once to warm up Room and the JIT, which a slow CI machine would otherwise count against the fold.
        assertThat(all()).hasSize(20_000)
        db.channels().setHidden(row("UK| Station0 HD").id, true)

        val start = System.nanoTime()
        val channels = all()
        val millis = (System.nanoTime() - start) / 1_000_000

        assertThat(channels).hasSize(20_000)
        assertThat(millis).isLessThan(FOLD_BUDGET_MILLIS)
    }

    private companion object {
        /** A big provider's whole guide; generous for a slow CI machine, far under the old minutes. */
        const val FOLD_BUDGET_MILLIS = 5_000L
    }
}
