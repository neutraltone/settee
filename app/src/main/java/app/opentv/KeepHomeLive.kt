/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv

import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.navigation.NavController
import androidx.navigation.compose.currentBackStackEntryAsState
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * Says in [keptLive] whether Home's rows should keep updating while Home isn't drawn (#192): while a
 * screen covers it with the app in front, so Back draws current rows at once instead of waiting for
 * them. Not under a player, which saves its last position only as it closes, after Home is drawn
 * again: Home waits for fresh rows there rather than highlight a card about to move or go, and its
 * queries don't run on every progress save. Not in the background, where nobody will press Back.
 */
@Composable
fun KeepHomeLive(nav: NavController, keptLive: MutableStateFlow<Boolean>) {
    val top by nav.currentBackStackEntryAsState()
    val lifecycle by LocalLifecycleOwner.current.lifecycle.currentStateFlow.collectAsState()
    val keep = lifecycle.isAtLeast(Lifecycle.State.STARTED) && coversHomeLive(top?.destination?.route)
    SideEffect { keptLive.value = keep }
}

private fun coversHomeLive(route: String?): Boolean =
    route != null && route != Routes.MAIN && !isPlayerRoute(route)
