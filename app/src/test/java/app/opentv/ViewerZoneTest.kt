/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv

import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import app.opentv.core.DeviceZone
import app.opentv.core.ViewerTime
import com.google.common.truth.Truth.assertThat
import java.time.ZoneId
import java.time.ZonedDateTime
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The viewer's zone sits on the graph beside its clock, and follows the device's when it changes. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class ViewerZoneTest {

    private val london = ZoneId.of("Europe/London")
    private val tokyo = ZoneId.of("Asia/Tokyo")
    private val zone = MutableStateFlow(london)

    @get:Rule
    val testGraph = TestGraph(zone = zone)

    @Test
    fun `the same instant reads differently once the graph's zone changes`() {
        val instant = ZonedDateTime.of(2026, 7, 4, 21, 30, 0, 0, london).toInstant().toEpochMilli()
        val before = ViewerTime.clockTime(instant, testGraph.graph.zone.value)

        zone.value = tokyo

        assertThat(before).isEqualTo("21:30")
        assertThat(ViewerTime.clockTime(instant, testGraph.graph.zone.value)).isEqualTo("05:30")
    }

    @Test
    fun `the device's zone is read again when Android says it has changed`() {
        var system = london
        val app = ApplicationProvider.getApplicationContext<android.app.Application>()
        val device = DeviceZone(app) { system }

        system = tokyo
        app.sendBroadcast(Intent(Intent.ACTION_TIMEZONE_CHANGED))
        testGraph.awaitIdle()

        assertThat(device.zone.value).isEqualTo(tokyo)
    }

    @Test
    fun `the zone Android's broadcast names wins over a default the process hasn't caught up with yet`() {
        val app = ApplicationProvider.getApplicationContext<android.app.Application>()
        val device = DeviceZone(app) { london }

        app.sendBroadcast(Intent(Intent.ACTION_TIMEZONE_CHANGED).putExtra(Intent.EXTRA_TIMEZONE, "Asia/Tokyo"))
        testGraph.awaitIdle()

        assertThat(device.zone.value).isEqualTo(tokyo)
    }

    @Test
    fun `a broadcast naming no zone Java knows falls back to the device's`() {
        var system = london
        val app = ApplicationProvider.getApplicationContext<android.app.Application>()
        val device = DeviceZone(app) { system }

        system = tokyo
        app.sendBroadcast(Intent(Intent.ACTION_TIMEZONE_CHANGED).putExtra(Intent.EXTRA_TIMEZONE, "Not/A_Zone"))
        testGraph.awaitIdle()

        assertThat(device.zone.value).isEqualTo(tokyo)
    }
}
