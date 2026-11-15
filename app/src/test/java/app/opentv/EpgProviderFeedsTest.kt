/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import app.opentv.data.model.Source
import app.opentv.data.model.SourceKind
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Each provider has a guide feed while it exists. A feed left behind by a provider deleted before
 * deleting took its guide with it goes the next time the feeds are checked (#343).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class EpgProviderFeedsTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val test = TestCatalog(context)
    private val db = test.db
    private val guide = test.guide

    @After
    fun close() = test.close()

    private suspend fun provider(name: String, enabled: Boolean = true) = db.sources().insert(
        Source(name = name, kind = SourceKind.M3U, url = "https://example.com/$name.m3u", enabled = enabled),
    )

    private suspend fun feeds() = guide.observeFeeds().first()

    @Test
    fun `a provider feed whose provider is gone is removed with its programmes and aliases`() = runTest {
        val gone = provider("Gone")
        val kept = provider("Kept")
        guide.ensureFeeds()
        test.seedProviderGuide(gone, "gone.news")
        test.seedProviderGuide(kept, "kept.news")
        // How a device that deleted a provider before #343 was left: the source row gone, its feed not.
        db.sources().delete(gone)

        guide.ensureFeeds()

        assertThat(feeds().mapNotNull { it.providerSourceId }).containsExactly(kept)
        assertThat(guide.upcoming("gone.news", nowUtcMillis = 0)).isEmpty()
        assertThat(guide.upcoming("kept.news", nowUtcMillis = 0).map { it.title }).containsExactly("News")
        assertThat(db.epgAliases().all().map { it.epgId }).containsExactly("kept.news")
    }

    @Test
    fun `a provider that's switched off keeps its feed, still switched on`() = runTest {
        val resting = provider("Resting")
        guide.ensureFeeds()
        db.sources().update(db.sources().byId(resting)!!.copy(enabled = false))

        guide.ensureFeeds()

        val feed = feeds().single { it.providerSourceId == resting }
        assertThat(feed.enabled).isTrue()
    }

    @Test
    fun `custom and built-in feeds are never removed as a gone provider's`() = runTest {
        val gone = provider("Gone")
        guide.addCustomFeed("My guide", "https://example.com/guide.xml")
        guide.ensureFeeds()
        val others = feeds().filter { it.providerSourceId == null }.map { it.name }
        db.sources().delete(gone)

        guide.ensureFeeds()

        assertThat(feeds().map { it.name }).containsExactlyElementsIn(others)
        assertThat(others).contains("My guide")
        assertThat(feeds().any { it.builtIn }).isTrue()
    }
}
