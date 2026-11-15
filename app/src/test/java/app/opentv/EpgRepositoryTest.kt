/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import app.opentv.data.model.Programme
import app.opentv.data.repo.EpgRepository
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class EpgRepositoryTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val catalogue = TestCatalog(context)
    private val guide = catalogue.guide

    @After
    fun close() = catalogue.close()

    private suspend fun programme(title: String, start: Long, end: Long) = catalogue.db.programmes().insertOrReplace(
        listOf(Programme(feedId = 1, epgChannelId = "itv", startUtcMillis = start, endUtcMillis = end, title = title)),
    )

    @Test
    fun `the programme on now is the one that has started and not ended`() = runTest {
        programme("Early", start = 0, end = 1_000)
        programme("Late", start = 1_000, end = 2_000)

        assertThat(guide.programmeOnNow("itv", nowUtcMillis = 500)?.title).isEqualTo("Early")
        assertThat(guide.programmeOnNow("itv", nowUtcMillis = 1_000)?.title).isEqualTo("Late")
        assertThat(guide.programmeOnNow("itv", nowUtcMillis = 2_000)).isNull()
    }

    @Test
    fun `a programme that hasn't started yet isn't on now`() = runTest {
        programme("Later", start = 1_000, end = 2_000)

        assertThat(guide.programmeOnNow("itv", nowUtcMillis = 999)).isNull()
    }

    @Test
    fun `a refreshed guide shows the provider's corrections to a programme it already had`() = runTest {
        catalogue.db.sources().insert(PANEL_SOURCE)
        catalogue.panel.replies["xmltv.php"] = feed(slot("c1", "10", "11", "Nwes", "First go"))
        guide.syncAll(nowUtcMillis = NOW)

        catalogue.panel.replies["xmltv.php"] = feed(slot("c1", "10", "12", "News", "Corrected"))
        guide.syncAll(nowUtcMillis = NOW, force = true)

        val stored = catalogue.db.programmes().startingAt("c1", TEN_O_CLOCK)
        assertThat(stored?.title).isEqualTo("News")
        assertThat(stored?.description).isEqualTo("Corrected")
        assertThat(stored?.endUtcMillis).isEqualTo(TEN_O_CLOCK + 2 * HOUR)
    }

    @Test
    fun `refreshing an unchanged guide doesn't add programmes`() = runTest {
        catalogue.db.sources().insert(PANEL_SOURCE)
        catalogue.panel.replies["xmltv.php"] = feed(
            slot("c1", "10", "11", "News", "Headlines"),
            slot("c1", "11", "12", "Weather", "Rain"),
            slot("c2", "10", "12", "Film", "A film"),
        )
        guide.syncAll(nowUtcMillis = NOW)

        guide.syncAll(nowUtcMillis = NOW, force = true)

        assertThat(storedProgrammes().map { it.title }).containsExactly("News", "Weather", "Film")
    }

    @Test
    fun `a programme left out of a refreshed guide stays until it's old enough to drop`() = runTest {
        catalogue.db.sources().insert(PANEL_SOURCE)
        catalogue.panel.replies["xmltv.php"] = feed(
            slot("c1", "10", "11", "News", "Headlines"),
            slot("c1", "11", "12", "Weather", "Rain"),
        )
        guide.syncAll(nowUtcMillis = NOW)

        catalogue.panel.replies["xmltv.php"] = feed(slot("c1", "10", "11", "News", "Headlines"))
        guide.syncAll(nowUtcMillis = NOW, force = true)

        assertThat(storedProgrammes().map { it.title }).containsExactly("News", "Weather")
    }

    @Test
    fun `a programme the refreshed guide moved is replaced by what's on in its place`() = runTest {
        catalogue.db.sources().insert(PANEL_SOURCE)
        catalogue.panel.replies["xmltv.php"] = feed(airing("c1", "2000", "2200", "Film"))
        guide.syncAll(nowUtcMillis = NOW)

        catalogue.panel.replies["xmltv.php"] = feed(
            airing("c1", "2005", "2035", "News"),
            airing("c1", "2035", "2130", "Drama"),
        )
        guide.syncAll(nowUtcMillis = NOW, force = true)

        assertThat(guide.nowAndNext("c1", TWENTY_FORTY).now?.title).isEqualTo("Drama")
        val evening = guide.observeGuideWindow(NOW + 19 * HOUR, NOW + 23 * HOUR).first()
        assertThat(evening.map { it.title }).containsExactly("News", "Drama").inOrder()
    }

    @Test
    fun `a refreshed guide that fails to download keeps the programmes it had`() = runTest {
        catalogue.db.sources().insert(PANEL_SOURCE)
        catalogue.panel.replies["xmltv.php"] = feed(airing("c1", "2000", "2200", "Film"))
        guide.syncAll(nowUtcMillis = NOW)

        catalogue.panel.replies["xmltv.php"] = "!"
        guide.syncAll(nowUtcMillis = NOW, force = true)

        assertThat(guide.nowAndNext("c1", TWENTY_FORTY).now?.title).isEqualTo("Film")
    }

    @Test
    fun `a refreshed guide cut off part way keeps the programmes it had`() = runTest {
        catalogue.db.sources().insert(PANEL_SOURCE)
        catalogue.panel.replies["xmltv.php"] = feed(airing("c1", "2000", "2200", "Film"))
        guide.syncAll(nowUtcMillis = NOW)

        // A full batch is written before the file ends mid-programme.
        val filler = (0 until EpgRepository.BATCH_SIZE).map { minute ->
            airing("c2", hhmm(minute), hhmm(minute + 1), "Short")
        }
        catalogue.panel.replies["xmltv.php"] = "<tv>" +
            airing("c1", "2005", "2035", "News") +
            airing("c1", "2035", "2130", "Drama") +
            filler.joinToString("") +
            """<programme start="20260101220000 +0000" stop="2026"""
        guide.syncAll(nowUtcMillis = NOW, force = true)

        assertThat(guide.nowAndNext("c1", TWENTY_FORTY).now?.title).isEqualTo("Film")
    }

    @Test
    fun `a programme too old to keep in a refreshed guide doesn't cost the ones that aired since`() = runTest {
        catalogue.db.sources().insert(PANEL_SOURCE)
        catalogue.panel.replies["xmltv.php"] = feed(airing("c1", "1200", "1300", "Lunchtime", day = "20251231"))
        guide.syncAll(nowUtcMillis = NOW)

        catalogue.panel.replies["xmltv.php"] = feed(
            airing("c1", "1000", "1100", "Long gone", day = "20251230"),
            airing("c1", "2005", "2035", "News"),
        )
        guide.syncAll(nowUtcMillis = NOW, force = true)

        assertThat(storedProgrammes().map { it.title }).containsExactly("Lunchtime", "News").inOrder()
    }

    @Test
    fun `a refreshed guide keeps the programmes of a channel it left out`() = runTest {
        catalogue.db.sources().insert(PANEL_SOURCE)
        catalogue.panel.replies["xmltv.php"] = feed(
            airing("c1", "2000", "2200", "Film"),
            airing("c2", "2000", "2100", "Quiz"),
        )
        guide.syncAll(nowUtcMillis = NOW)

        catalogue.panel.replies["xmltv.php"] = feed(
            airing("c1", "2005", "2035", "News"),
            airing("c1", "2035", "2130", "Drama"),
        )
        guide.syncAll(nowUtcMillis = NOW, force = true)

        assertThat(guide.nowAndNext("c2", TWENTY_FORTY).now?.title).isEqualTo("Quiz")
    }

    @Test
    fun `a refreshed guide covering less of the day keeps the programmes before and after it`() = runTest {
        catalogue.db.sources().insert(PANEL_SOURCE)
        catalogue.panel.replies["xmltv.php"] = feed(
            airing("c1", "0800", "0900", "Breakfast"),
            airing("c1", "2000", "2200", "Film"),
            airing("c1", "2200", "2300", "Late"),
        )
        guide.syncAll(nowUtcMillis = NOW)

        catalogue.panel.replies["xmltv.php"] = feed(
            airing("c1", "2005", "2035", "News"),
            airing("c1", "2035", "2130", "Drama"),
        )
        guide.syncAll(nowUtcMillis = NOW, force = true)

        assertThat(storedProgrammes().map { it.title }).containsExactly("Breakfast", "News", "Drama", "Late").inOrder()
    }

    @Test
    fun `a refreshed guide leaves another guide's programmes for the same channel alone`() = runTest {
        catalogue.db.sources().insert(PANEL_SOURCE)
        guide.addCustomFeed("Other", "http://example.com/other/xmltv.php")
        catalogue.panel.replies["xmltv.php"] = feed(airing("c1", "2000", "2200", "Film"))
        guide.syncAll(nowUtcMillis = NOW)
        val other = guide.observeFeeds().first().single { it.name == "Other" }
        guide.setFeedEnabled(other, enabled = false)

        catalogue.panel.replies["xmltv.php"] = feed(
            airing("c1", "2005", "2035", "News"),
            airing("c1", "2035", "2130", "Drama"),
        )
        guide.syncAll(nowUtcMillis = NOW, force = true)

        assertThat(storedProgrammes().map { it.title }).containsExactly("Film", "News", "Drama").inOrder()
    }

    @Test
    fun `the guide window holds only the programmes overlapping it, in channel then start order`() = runTest {
        programme("Before", start = 0, end = 1_000)
        programme("Overlaps the start", start = 500, end = 1_500)
        catalogue.db.programmes().insertOrReplace(
            listOf(
                Programme(
                    feedId = 1,
                    epgChannelId = "bbc",
                    startUtcMillis = 1_800,
                    endUtcMillis = 2_500,
                    title = "Inside",
                ),
            ),
        )
        programme("Inside too", start = 1_500, end = 1_900)
        programme("After", start = 2_000, end = 3_000)

        val window = guide.observeGuideWindow(fromUtcMillis = 1_000, toUtcMillis = 2_000).first()

        assertThat(window.map { it.epgChannelId to it.title }).containsExactly(
            "bbc" to "Inside",
            "itv" to "Overlaps the start",
            "itv" to "Inside too",
        ).inOrder()
    }

    @Test
    fun `a programme in the guide window is found in full with its description`() = runTest {
        catalogue.db.sources().insert(PANEL_SOURCE)
        catalogue.panel.replies["xmltv.php"] = feed(slot("c1", "10", "11", "News", "Headlines"))
        guide.syncAll(nowUtcMillis = NOW)
        val listed = guide.observeGuideWindow(0, Long.MAX_VALUE).first().single()

        val full = guide.programme(listed)

        assertThat(full?.title).isEqualTo("News")
        assertThat(full?.description).isEqualTo("Headlines")
    }

    @Test
    fun `a programme listed before the guide refreshed is still found, with the refreshed description`() = runTest {
        catalogue.db.sources().insert(PANEL_SOURCE)
        catalogue.panel.replies["xmltv.php"] = feed(slot("c1", "10", "11", "News", "First go"))
        guide.syncAll(nowUtcMillis = NOW)
        val listed = guide.observeGuideWindow(0, Long.MAX_VALUE).first().single()

        catalogue.panel.replies["xmltv.php"] = feed(slot("c1", "10", "11", "News", "Corrected"))
        guide.syncAll(nowUtcMillis = NOW, force = true)

        assertThat(guide.programme(listed)?.description).isEqualTo("Corrected")
    }

    @Test
    fun `a programme two feeds list is found with the description either of them has`() = runTest {
        catalogue.db.programmes().insertOrReplace(
            listOf(
                Programme(feedId = 1, epgChannelId = "itv", startUtcMillis = 0, endUtcMillis = 1_000, title = "News"),
                Programme(
                    feedId = 2,
                    epgChannelId = "itv",
                    startUtcMillis = 0,
                    endUtcMillis = 1_000,
                    title = "News",
                    description = "Headlines",
                ),
            ),
        )
        val listed = guide.observeGuideWindow(0, Long.MAX_VALUE).first().first()

        assertThat(guide.programme(listed)?.description).isEqualTo("Headlines")
    }

    @Test
    fun `a programme the guide has dropped isn't found`() = runTest {
        programme("News", start = 0, end = 1_000)
        val listed = guide.observeGuideWindow(0, Long.MAX_VALUE).first().single()

        catalogue.db.programmes().deleteEndedBefore(2_000)

        assertThat(guide.programme(listed)).isNull()
    }

    private fun slot(channel: String, from: String, to: String, title: String, description: String) =
        """<programme start="20260101${from}0000 +0000" stop="20260101${to}0000 +0000" channel="$channel">""" +
            "<title>$title</title><desc>$description</desc></programme>"

    /** A programme on [day] (`YYYYMMDD`, 2026-01-01 unless given) from [from] to [to], each `HHMM`. */
    private fun airing(channel: String, from: String, to: String, title: String, day: String = "20260101") =
        """<programme start="$day${from}00 +0000" stop="$day${to}00 +0000" channel="$channel">""" +
            "<title>$title</title></programme>"

    private fun hhmm(minutes: Int) = "%02d%02d".format(minutes / 60, minutes % 60)

    private fun feed(vararg slots: String) = "<tv>${slots.joinToString("")}</tv>"

    private suspend fun storedProgrammes() = guide.observeGuideWindow(0, Long.MAX_VALUE).first()

    private companion object {
        /** 2026-01-01, when the guide's programmes are. */
        const val NOW = 1_767_225_600_000L
        const val HOUR = 3_600_000L
        const val TEN_O_CLOCK = NOW + 10 * HOUR
        const val TWENTY_FORTY = NOW + 20 * HOUR + 40 * 60_000L
    }
}
