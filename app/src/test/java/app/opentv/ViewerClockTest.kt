/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv

import android.app.Application
import android.provider.Settings
import androidx.test.core.app.ApplicationProvider
import app.opentv.core.ViewerClock
import com.google.common.truth.Truth.assertThat
import java.time.LocalDateTime
import java.time.ZoneId
import java.util.Locale
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Clock times and day labels as the viewer reads them: in the app's current language, the device's
 * 12- or 24-hour clock and the viewer's zone, each read when a time is drawn.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "en-rGB", application = android.app.Application::class)
class ViewerClockTest {

    private val context = ApplicationProvider.getApplicationContext<Application>()
    private val locale = Locale.getDefault()
    private val london = ZoneId.of("Europe/London")
    private val clock = ViewerClock.of(context) { london }

    // Saturday 4 July 2026, 8pm in London.
    private val saturdayEvening = LocalDateTime.parse("2026-07-04T20:00").atZone(london).toInstant().toEpochMilli()

    @After
    fun restore() = Locale.setDefault(locale)

    /** The device's clock setting, as Settings > Date & time sets it. */
    private fun deviceClock(hours: Int) {
        Settings.System.putString(context.contentResolver, Settings.System.TIME_12_24, hours.toString())
    }

    /** The app's chosen language, as Settings > Language saves it. */
    private fun appLanguage(tag: String) {
        context.getSharedPreferences("opentv_settings", android.content.Context.MODE_PRIVATE)
            .edit().putString("language_tag", tag).commit()
    }

    @Test
    fun `a device set to a 12-hour clock reads 8 pm, even in a language that writes 24-hour times`() {
        deviceClock(12)

        // British English's own way of writing it: lower case, after a narrow no-break space.
        assertThat(clock.formatTime(saturdayEvening)).isEqualTo("8:00 pm")
    }

    @Test
    fun `a device set to a 24-hour clock reads 20 00, even in a language that writes 12-hour times`() {
        deviceClock(24)
        appLanguage("en-US")

        assertThat(clock.formatTime(saturdayEvening)).isEqualTo("20:00")
    }

    @Test
    fun `American English on a 12-hour clock reads 8 PM`() {
        deviceClock(12)
        appLanguage("en-US")

        assertThat(clock.formatTime(saturdayEvening)).isEqualTo("8:00 PM")
    }

    @Test
    fun `a day label is in Japanese order in Japanese`() {
        appLanguage("ja")

        assertThat(clock.formatDay(saturdayEvening)).isEqualTo("7月4日(土)")
    }

    @Test
    fun `day names change with the app's language straight away, without a new process`() {
        appLanguage("en-GB")
        val before = clock.formatDay(saturdayEvening)

        appLanguage("de")

        assertThat(before).isEqualTo("Sat, 4 Jul")
        assertThat(clock.formatDay(saturdayEvening)).isEqualTo("Sa., 4. Juli")
    }

    @Test
    fun `a day and time are one phrase, on the device's clock`() {
        appLanguage("en-GB")
        deviceClock(24)

        assertThat(clock.formatDayTime(saturdayEvening)).isEqualTo("Sat, 4 Jul, 20:00")
    }

    @Test
    fun `the zone is read each time, so a time moves when the device's zone does`() {
        deviceClock(24)
        var zone = london
        val travelling = ViewerClock.of(context) { zone }
        val before = travelling.formatDayTime(saturdayEvening)

        zone = ZoneId.of("Asia/Tokyo")

        assertThat(before).isEqualTo("Sat, 4 Jul, 20:00")
        assertThat(travelling.formatDayTime(saturdayEvening)).isEqualTo("Sun, 5 Jul, 04:00")
    }
}
