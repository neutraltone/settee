/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv

import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * How far through the guide a download has got, for Setup's bar. On a Stick nearly all of a big
 * provider's guide time was one feed, so counting feeds done would hold the bar still.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class GuideProgressTest {

    private val catalogue = TestCatalog(ApplicationProvider.getApplicationContext())
    private val guide = catalogue.guide

    @After
    fun close() = catalogue.close()

    @Test
    fun `a feed whose size is known moves the progress while it's read, never backwards, to the end`() = runTest {
        catalogue.db.sources().insert(PANEL_SOURCE)
        catalogue.panel.replies["xmltv.php"] = bigGuide(programmes = 3_000)
        val reported = mutableListOf<Float>()

        guide.syncAll(nowUtcMillis = NOW, progress = { reported += it.share })

        // Somewhere in the middle of reading the one feed, not only at its ends.
        assertThat(reported.filter { it > 0.2f && it < 0.8f }).isNotEmpty()
        assertThat(reported).isInOrder()
        assertThat(reported.last()).isEqualTo(1f)
    }

    @Test
    fun `a feed whose size isn't known fills its share once it's done`() = runTest {
        catalogue.db.sources().insert(PANEL_SOURCE)
        catalogue.panel.replies["xmltv.php"] = bigGuide(programmes = 3_000)
        catalogue.panel.unsized += "xmltv.php"
        val reported = mutableListOf<Float>()

        guide.syncAll(nowUtcMillis = NOW, progress = { reported += it.share })

        // Setting up the feeds, the one feed, matching: the shares measured on a Stick.
        assertThat(reported.distinct()).containsExactly(0.07f, 0.92f, 1f).inOrder()
    }

    @Test
    fun `a feed whose size isn't known still says how many programmes it has read, as it reads them`() = runTest {
        catalogue.db.sources().insert(PANEL_SOURCE)
        catalogue.panel.replies["xmltv.php"] = bigGuide(programmes = 3_000)
        catalogue.panel.unsized += "xmltv.php"
        val counts = mutableListOf<Int>()

        guide.syncAll(nowUtcMillis = NOW, progress = { counts += it.programmes })

        assertThat(counts.filter { it in 1..2_999 }).isNotEmpty()
        assertThat(counts).isInOrder()
        assertThat(counts.last()).isEqualTo(3_000)
    }

    private fun bigGuide(programmes: Int) = buildString {
        append("<tv>")
        repeat(programmes) { i ->
            val hour = "%02d".format(i % 24)
            append("""<programme start="20260101${hour}0000 +0000" stop="20260101${hour}3000 +0000" channel="c$i">""")
            append("<title>Programme $i</title></programme>")
        }
        append("</tv>")
    }

    private companion object {
        /** 2026-01-01, when the guide's programmes are. */
        const val NOW = 1_767_225_600_000L
    }
}
