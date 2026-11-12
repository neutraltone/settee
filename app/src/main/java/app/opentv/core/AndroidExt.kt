/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv.core

import android.annotation.SuppressLint
import android.app.Activity
import android.content.ComponentName
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.pm.PackageManager
import android.os.PowerManager
import android.provider.Settings
import androidx.core.net.toUri

/**
 * The [Activity] behind a Compose [Context], unwrapping the `ContextWrapper` chain Compose hands
 * you. Needed for the handful of things that are genuinely window-level — like holding the screen
 * awake during playback — where a view flag isn't enough on every TV box.
 */
tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}

/**
 * Whether the OS is currently exempting OpenTV from battery optimisation ("Doze"). When it isn't,
 * Android TV / Fire TV are free to freeze the app while the box is in standby, which stops an
 * in-progress or scheduled recording dead — the single biggest reason captures don't survive the
 * device going to sleep.
 */
fun Context.isIgnoringBatteryOptimizations(): Boolean {
    val pm = getSystemService(Context.POWER_SERVICE) as? PowerManager ?: return true
    return pm.isIgnoringBatteryOptimizations(packageName)
}

/**
 * The system screens where a viewer can let OpenTV ignore battery optimisation, best first: the
 * request for OpenTV itself, then the list of every app, which is all a few TV boxes ship. Empty
 * where the device has neither, so there is nothing to offer.
 *
 * This asks what would answer each request instead of starting it and seeing whether that throws,
 * because Fire OS answers both with one activity that closes as it opens (its
 * `CTSDummyIntentHandler`, there to pass a compatibility test): starting it succeeds, grants nothing
 * and leaves the viewer wherever closing it lands them. A request for one app and a list of them all
 * are different screens, so one activity answering both is taken for such a stub, whoever made it.
 */
@SuppressLint("BatteryLife")
fun batteryOptimizationRequests(packages: PackageManager, packageName: String): List<Intent> {
    val request = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, "package:$packageName".toUri())
    val list = Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
    val requestScreen = packages.screenFor(request)
    val listScreen = packages.screenFor(list)
    if (requestScreen != null && requestScreen == listScreen) return emptyList()
    return listOfNotNull(request.takeIf { requestScreen != null }, list.takeIf { listScreen != null })
}

private fun PackageManager.screenFor(intent: Intent): ComponentName? =
    resolveActivity(intent, PackageManager.MATCH_DEFAULT_ONLY)
        ?.activityInfo
        ?.let { ComponentName(it.packageName, it.name) }

/**
 * Opens the system screen that asks the user to let OpenTV ignore battery optimisation — the
 * exemption a set-top box relies on to keep recording while asleep. This is the correct,
 * user-granted flow; it cannot (and should not) be granted silently. Does nothing on a device with
 * no such screen ([batteryOptimizationRequests]): check there before offering it.
 */
fun Context.requestIgnoreBatteryOptimizations() {
    for (request in batteryOptimizationRequests(packageManager, packageName)) {
        // NO_USER_ACTION: Android otherwise tells the activity its viewer is leaving, as it does for
        // Home, and a playing channel shrinks into picture-in-picture behind the screen they were
        // sent to (MainActivity.onUserLeaveHint).
        request.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_USER_ACTION)
        if (runCatching { startActivity(request) }.isSuccess) return
    }
}
