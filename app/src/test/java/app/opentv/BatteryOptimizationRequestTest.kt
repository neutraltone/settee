/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv

import android.app.Application
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.PowerManager
import android.provider.Settings
import androidx.test.core.app.ApplicationProvider
import app.opentv.core.batteryOptimizationRequests
import app.opentv.core.requestIgnoreBatteryOptimizations
import app.opentv.ui.RecordingBackgroundPrompt
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * Where "Allow" on "Keep recording when you leave?" sends the viewer, on devices with and without a
 * screen for it. A Fire TV Stick answers both requests with one stub that closes at once (#233).
 */
// Plain Application: OpenTvApp schedules WorkManager on start, which none of this needs.
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class BatteryOptimizationRequestTest {

    private val app: Application = ApplicationProvider.getApplicationContext()

    @Test
    fun `a device with the request screen is asked for Settee itself, then for the list of apps`() {
        REQUEST_SCREEN.answers(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, scheme = "package")
        LIST_SCREEN.answers(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)

        val requests = batteryOptimizationRequests(app.packageManager, app.packageName)

        assertThat(requests.map { it.action }).containsExactly(
            Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
            Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS,
        ).inOrder()
        assertThat(requests.first().data.toString()).isEqualTo("package:${app.packageName}")
    }

    @Test
    fun `a device with only the list of apps is sent to the list`() {
        LIST_SCREEN.answers(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)

        val requests = batteryOptimizationRequests(app.packageManager, app.packageName)

        assertThat(requests.map { it.action })
            .containsExactly(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
    }

    @Test
    fun `a device with no screen for it has nowhere to send the viewer`() {
        assertThat(batteryOptimizationRequests(app.packageManager, app.packageName)).isEmpty()
    }

    @Test
    fun `one activity answering both requests is a stub, not a screen`() {
        fireTvStick()

        assertThat(batteryOptimizationRequests(app.packageManager, app.packageName)).isEmpty()
    }

    @Test
    fun `the dialog is offered where the request screen exists, until Settee is allowed`() {
        REQUEST_SCREEN.answers(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, scheme = "package")
        LIST_SCREEN.answers(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)

        assertThat(RecordingBackgroundPrompt.shouldShow(app)).isTrue()

        val power = app.getSystemService(Context.POWER_SERVICE) as PowerManager
        shadowOf(power).setIgnoringBatteryOptimizations(app.packageName, true)
        assertThat(RecordingBackgroundPrompt.shouldShow(app)).isFalse()
    }

    @Test
    fun `the dialog is never offered where Allow would have nowhere to go`() {
        fireTvStick()

        assertThat(RecordingBackgroundPrompt.shouldShow(app)).isFalse()
    }

    @Test
    fun `asking opens the request screen without telling Settee the viewer has left`() {
        REQUEST_SCREEN.answers(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, scheme = "package")
        LIST_SCREEN.answers(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)

        app.requestIgnoreBatteryOptimizations()

        val started = shadowOf(app).nextStartedActivity
        assertThat(started.action).isEqualTo(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
        // Without this the player takes the system screen for the viewer pressing Home, and shrinks
        // into picture-in-picture behind it.
        assertThat(started.flags and Intent.FLAG_ACTIVITY_NO_USER_ACTION).isNotEqualTo(0)
        assertThat(shadowOf(app).nextStartedActivity).isNull()
    }

    @Test
    fun `asking opens nothing where there's only a stub`() {
        fireTvStick()

        app.requestIgnoreBatteryOptimizations()

        assertThat(shadowOf(app).nextStartedActivity).isNull()
    }

    /** As the Stick resolves them: `adb shell cmd package resolve-activity` names this for both. */
    private fun fireTvStick() {
        AMAZON_STUB.answers(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, scheme = "package")
        AMAZON_STUB.answers(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
    }

    private fun ComponentName.answers(action: String, scheme: String? = null) {
        val packages = shadowOf(app.packageManager)
        packages.addActivityIfNotPresent(this)
        packages.addIntentFilterForActivity(
            this,
            IntentFilter(action).apply {
                addCategory(Intent.CATEGORY_DEFAULT)
                scheme?.let(::addDataScheme)
            },
        )
    }

    private companion object {
        val REQUEST_SCREEN =
            ComponentName("com.android.settings", "com.android.settings.fuelgauge.RequestIgnoreBatteryOptimizations")
        val LIST_SCREEN =
            ComponentName("com.android.settings", "com.android.settings.Settings\$HighPowerApplicationsActivity")
        val AMAZON_STUB = ComponentName(
            "com.amazon.tv.settings.v2",
            "com.amazon.tv.settings.v2.compliance.CTSDummyIntentHandler",
        )
    }
}
