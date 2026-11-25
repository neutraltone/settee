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
 *
 * The broadcast's own zone comes first: the process's default is updated by its own receiver of the
 * same broadcast, in no promised order, so reading it here could still give the old zone.
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
                    current.value = named(intent) ?: read()
                }
            },
            IntentFilter(Intent.ACTION_TIMEZONE_CHANGED),
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
    }

    private companion object {
        // Intent.EXTRA_TIMEZONE, which Android has sent with this broadcast all along but only named
        // as a constant in API 30: spelt out so it reads the same below that.
        const val EXTRA_TIMEZONE = "time-zone"

        /** The zone [intent] names, or null when it names none Java knows. */
        fun named(intent: Intent): ZoneId? =
            intent.getStringExtra(EXTRA_TIMEZONE)?.let { runCatching { ZoneId.of(it) }.getOrNull() }
    }
}
