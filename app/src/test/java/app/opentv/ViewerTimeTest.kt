/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv

import app.opentv.core.ViewerTime
import com.google.common.truth.Truth.assertThat
import java.time.LocalDateTime
import java.time.ZoneId
import java.util.Locale
import org.junit.Test

/** The viewer's local day and clock time, worked out in a zone the test names, never the JVM's. */
class ViewerTimeTest {

    private val london = ZoneId.of("Europe/London")

    /** [local], such as `2026-03-28T21:47`, read in [zone], as UTC epoch millis. */
    private fun at(zone: ZoneId, local: String): Long =
        LocalDateTime.parse(local).atZone(zone).toInstant().toEpochMilli()

    @Test
    fun `today runs from the current half hour to local midnight`() {
        val now = at(london, "2026-03-28T21:47")

        val window = ViewerTime.guideWindow(now, dayOffset = 0, zone = london)

        assertThat(window.startUtcMillis).isEqualTo(at(london, "2026-03-28T21:30"))
        assertThat(window.endUtcMillis).isEqualTo(at(london, "2026-03-29T00:00"))
    }

    @Test
    fun `a later day runs from its local midnight to the next, however long the clocks make it`() {
        // The clocks go forward on 29 March 2026 in London, so that day is 23 hours long.
        val now = at(london, "2026-03-28T21:47")

        val window = ViewerTime.guideWindow(now, dayOffset = 1, zone = london)

        assertThat(window.startUtcMillis).isEqualTo(at(london, "2026-03-29T00:00"))
        assertThat(window.endUtcMillis).isEqualTo(at(london, "2026-03-30T00:00"))
        assertThat(window.endUtcMillis - window.startUtcMillis).isEqualTo(23 * 60 * 60 * 1000L)
    }

    @Test
    fun `two days on from the Saturday the clocks go back is Monday, midnight to midnight`() {
        val now = at(london, "2025-10-25T12:00")

        val window = ViewerTime.guideWindow(now, dayOffset = 2, zone = london)

        assertThat(window.startUtcMillis).isEqualTo(at(london, "2025-10-27T00:00"))
        assertThat(window.endUtcMillis).isEqualTo(at(london, "2025-10-28T00:00"))
    }

    @Test
    fun `the day the clocks go back, today runs to Monday's midnight, 25 hours after Sunday's`() {
        val now = at(london, "2025-10-26T12:00")

        val window = ViewerTime.guideWindow(now, dayOffset = 0, zone = london)

        assertThat(window.endUtcMillis).isEqualTo(at(london, "2025-10-27T00:00"))
        assertThat(window.endUtcMillis - at(london, "2025-10-26T00:00")).isEqualTo(25 * 60 * 60 * 1000L)
    }

    @Test
    fun `today starts on the viewer's half hour, not UTC's, where the zone is 45 minutes off it`() {
        val kathmandu = ZoneId.of("Asia/Kathmandu")
        val now = at(kathmandu, "2026-03-28T21:47")

        val window = ViewerTime.guideWindow(now, dayOffset = 0, zone = kathmandu)

        assertThat(window.startUtcMillis).isEqualTo(at(kathmandu, "2026-03-28T21:30"))
    }

    @Test
    fun `a localised time and date are in the locale given, and the zone`() {
        val instant = at(london, "2026-07-04T21:30")

        assertThat(ViewerTime.localTime(instant, london, Locale.UK)).isEqualTo("21:30")
        assertThat(ViewerTime.localTime(instant, ZoneId.of("Asia/Tokyo"), Locale.UK)).isEqualTo("05:30")
        assertThat(ViewerTime.localDate(instant, london, Locale.UK)).isEqualTo("4 Jul 2026")
        assertThat(ViewerTime.localDate(instant, ZoneId.of("Asia/Tokyo"), Locale.UK)).isEqualTo("5 Jul 2026")
    }

    @Test
    fun `a clock time and a day label are the instant's in the zone given`() {
        val instant = at(london, "2026-07-04T00:15")

        assertThat(ViewerTime.clockTime(instant, london)).isEqualTo("00:15")
        assertThat(ViewerTime.dayLabel(instant, london)).isEqualTo("Sat 4 Jul")
        assertThat(ViewerTime.clockTime(instant, ZoneId.of("America/New_York"))).isEqualTo("19:15")
        assertThat(ViewerTime.dayLabel(instant, ZoneId.of("America/New_York"))).isEqualTo("Fri 3 Jul")
    }
}
