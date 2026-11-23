/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import app.opentv.core.ViewerError
import app.opentv.data.model.EpgFeed
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.flow.first
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** What Guide settings says about a guide that wouldn't download (#471). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class GuideDownloadFailureTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val catalogue = TestCatalog(context)
    private val guide = catalogue.guide

    @After
    fun close() = catalogue.close()

    /** Saves a guide of the viewer's own answering [reply], downloads it, and returns why it failed. */
    private suspend fun ownGuideAnswering(reply: String): ViewerError {
        catalogue.panel.replies["xmltv.php"] = reply
        guide.addCustomFeed("Own guide", OWN_GUIDE)
        guide.syncAll(nowUtcMillis = NOW, force = true)
        return checkNotNull(ViewerError.fromStored(checkNotNull(guide.savedFeed(OWN_GUIDE)).lastResult))
    }

    private fun string(id: Int) = context.getString(id)

    @Test
    fun `an own guide's address that answers 404 says it isn't a guide, with the status`() = testing {
        val failure = ownGuideAnswering("!404")

        assertThat(failure.sentence).isEqualTo(string(R.string.error_not_a_guide))
        assertThat(failure.detail).contains("HTTP 404")
    }

    @Test
    fun `an own guide's address that's gone or wants a login says it isn't a guide, with the status`() = testing {
        for (status in listOf(410, 401, 403)) {
            val failure = ownGuideAnswering("!$status")

            assertThat(failure.sentence).isEqualTo(string(R.string.error_not_a_guide))
            assertThat(failure.detail).contains("HTTP $status")
        }
    }

    @Test
    fun `an own guide's server having trouble says to try again, with the status`() = testing {
        val failure = ownGuideAnswering("!503")

        assertThat(failure.sentence).isEqualTo(string(R.string.error_guide))
        assertThat(failure.detail).contains("HTTP 503")
    }

    @Test
    fun `an own guide's address that answers with a web page says it isn't a guide`() = testing {
        val failure = ownGuideAnswering("<!DOCTYPE html><html><head><title>Sign in</title></head><body></body></html>")

        assertThat(failure.sentence).isEqualTo(string(R.string.error_not_a_guide))
        assertThat(failure.detail).isEqualTo("NotAGuide")
    }

    @Test
    fun `an own guide's address that answers with a page that isn't even XML says it isn't a guide`() = testing {
        for (page in listOf("Service unavailable", "<html lang=en><body>Sign in</body></html>")) {
            val failure = ownGuideAnswering(page)

            assertThat(failure.sentence).isEqualTo(string(R.string.error_not_a_guide))
            assertThat(failure.detail).isEqualTo("NotAGuide")
        }
    }

    @Test
    fun `an own guide saved with an address that won't parse says it isn't a guide`() = testing {
        catalogue.db.epgFeeds().insert(EpgFeed(name = "Old guide", url = "not a url", enabled = true))

        guide.syncAll(nowUtcMillis = NOW, force = true)

        val failure = checkNotNull(ViewerError.fromStored(checkNotNull(guide.savedFeed("not a url")).lastResult))
        assertThat(failure.sentence).isEqualTo(string(R.string.error_not_a_guide))
        assertThat(failure.detail).isEqualTo("GuideAddress")
    }

    @Test
    fun `a gzipped guide still downloads`() = testing {
        catalogue.panel.gzipped += "xmltv.php"
        catalogue.panel.replies["xmltv.php"] = GUIDE
        guide.addCustomFeed("Own guide", OWN_GUIDE)

        val summary = guide.syncAll(nowUtcMillis = NOW, force = true)

        assertThat(summary.feedsFailed).isEqualTo(0)
        assertThat(guide.programmeOnNow("news", NOW + 10 * HOUR + 1)?.title).isEqualTo("News")
    }

    /** Downloads the provider's own guide, answering [reply], and returns why it failed. */
    private suspend fun providersGuideAnswering(reply: String): ViewerError {
        catalogue.db.sources().insert(PANEL_SOURCE)
        catalogue.panel.replies["xmltv.php"] = reply
        guide.syncAll(nowUtcMillis = NOW, force = true)
        val feed = guide.observeFeeds().first().single { it.providerSourceId != null }
        return checkNotNull(ViewerError.fromStored(feed.lastResult))
    }

    @Test
    fun `the provider's guide turning down the login says so, with the status`() = testing {
        val failure = providersGuideAnswering("!401")

        assertThat(failure.sentence).isEqualTo(string(R.string.error_login))
        assertThat(failure.detail).contains("HTTP 401")
    }

    @Test
    fun `the provider's guide having trouble says it's at the provider's end, with the status`() = testing {
        val failure = providersGuideAnswering("!503")

        assertThat(failure.sentence).isEqualTo(string(R.string.error_http_5xx))
        assertThat(failure.detail).contains("HTTP 503")
    }

    @Test
    fun `the provider's guide link answering with a web page says it isn't a guide`() = testing {
        val failure = providersGuideAnswering("<html><body>Welcome</body></html>")

        assertThat(failure.sentence).isEqualTo(string(R.string.error_not_a_guide))
        assertThat(failure.detail).isEqualTo("NotAGuide")
    }

    @Test
    fun `a guide that stops being one keeps the programmes it already gave`() = testing {
        catalogue.panel.replies["xmltv.php"] = GUIDE
        guide.addCustomFeed("Own guide", OWN_GUIDE)
        guide.syncAll(nowUtcMillis = NOW, force = true)

        catalogue.panel.replies["xmltv.php"] = "<html></html>"
        guide.syncAll(nowUtcMillis = NOW, force = true)

        assertThat(guide.programmeOnNow("news", NOW + 10 * HOUR + 1)?.title).isEqualTo("News")
    }

    private companion object {
        const val HOUR = 3_600_000L
        const val GUIDE = """<?xml version="1.0"?><tv><programme start="20260101100000 +0000" """ +
            """stop="20260101110000 +0000" channel="news"><title>News</title></programme></tv>"""

        const val OWN_GUIDE = "http://example.com/guides/xmltv.php"

        /** 2026-01-01. */
        const val NOW = 1_767_225_600_000L
    }
}
