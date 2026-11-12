/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv

import android.app.Application
import android.content.ComponentName
import android.content.Intent
import android.content.IntentFilter
import android.provider.Settings
import org.robolectric.Shadows.shadowOf

/**
 * What a test's device opens when asked to let Settee ignore battery optimisation. Robolectric's own
 * answer is nothing at all.
 */
object BatteryScreens {

    /** The request for one app and the list of them all, as stock Android has them. */
    fun both(app: Application) {
        requestOnly(app)
        listOnly(app)
    }

    fun requestOnly(app: Application) =
        app.answers(REQUEST, Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, scheme = "package")

    /** All a few TV boxes ship. */
    fun listOnly(app: Application) = app.answers(LIST, Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)

    /**
     * A Fire TV Stick, where one activity answers both and closes as it opens (#233): `adb shell cmd
     * package resolve-activity` names this for each.
     */
    fun fireTvStub(app: Application) {
        app.answers(AMAZON_STUB, Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, scheme = "package")
        app.answers(AMAZON_STUB, Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
    }

    private fun Application.answers(screen: ComponentName, action: String, scheme: String? = null) {
        val packages = shadowOf(packageManager)
        packages.addActivityIfNotPresent(screen)
        packages.addIntentFilterForActivity(
            screen,
            IntentFilter(action).apply {
                addCategory(Intent.CATEGORY_DEFAULT)
                scheme?.let(::addDataScheme)
            },
        )
    }

    private val REQUEST =
        ComponentName("com.android.settings", "com.android.settings.fuelgauge.RequestIgnoreBatteryOptimizations")
    private val LIST =
        ComponentName("com.android.settings", "com.android.settings.Settings\$HighPowerApplicationsActivity")
    private val AMAZON_STUB = ComponentName(
        "com.amazon.tv.settings.v2",
        "com.amazon.tv.settings.v2.compliance.CTSDummyIntentHandler",
    )
}
