/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv

import androidx.navigation.NavController
import androidx.navigation.NavGraphBuilder
import androidx.navigation.compose.composable
import app.opentv.ui.settings.FontLicencesScreen
import app.opentv.ui.settings.SettingsMemory
import app.opentv.ui.settings.SettingsScreen

/**
 * Settings, one route for every section, and the screens its rows open that aren't anyone else's.
 * Kept apart from the rest of the graph, as [providerRoutes] is, so a test can build them with the
 * real screens and check where each row leads. The channel manager's route is the app's own.
 */
fun NavGraphBuilder.settingsRoutes(nav: NavController, memory: SettingsMemory) {
    composable(Routes.SETTINGS) {
        SettingsScreen(
            memory = memory,
            onBack = { nav.popBackStack() },
            onAddProvider = { nav.openAddProvider() },
            onEditProvider = { id -> nav.navigate(Routes.editSource(id)) },
            onOpenChannels = { nav.navigate(Routes.CHANNELS) },
            onOpenFontLicences = { nav.navigate(Routes.FONT_LICENCES) },
        )
    }

    composable(Routes.FONT_LICENCES) {
        FontLicencesScreen(onBack = { nav.popBackStack() })
    }
}
