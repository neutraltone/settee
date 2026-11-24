/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv.core

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import androidx.core.content.ContextCompat
import java.time.ZoneId
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * The device's time zone, read again whenever Android announces it has changed: travelling, or the
 * viewer picking another in Settings, moves the guide's days and times without restarting the app.
 * [read] is the system's zone; a test passes its own rather than changing the JVM's default.
 */
class DeviceZone(context: Context, private val read: () -> ZoneId = ZoneId::systemDefault) {

    private val current = MutableStateFlow(read())
    val zone: StateFlow<ZoneId> = current.asStateFlow()

    init {
        // Lives as long as the process, as the graph that holds it does, so it's never unregistered.
        ContextCompat.registerReceiver(
            context.applicationContext,
            object : BroadcastReceiver() {
                override fun onReceive(context: Context, intent: Intent) {
                    current.value = read()
                }
            },
            IntentFilter(Intent.ACTION_TIMEZONE_CHANGED),
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
    }
}
