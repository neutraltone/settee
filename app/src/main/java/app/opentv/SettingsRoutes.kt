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
import app.opentv.ui.settings.HomeRowsScreen
import app.opentv.ui.settings.LockedCategoriesScreen
import app.opentv.ui.settings.SettingsMemory
import app.opentv.ui.settings.SettingsScreen
import app.opentv.ui.settings.SettingsSection
import app.opentv.ui.settings.providerRow

/**
 * Settings, one route for every section, and the screens its rows open that aren't anyone else's.
 * Kept apart from the rest of the graph, as [providerRoutes] is, so a test can build them with the
 * real screens and check where each row leads. The channel manager's route is the app's own.
 */
/** Settings › Providers, with the Highlight on [sourceId]'s row, as Home's banner opens it. */
fun NavController.openProviders(memory: SettingsMemory, sourceId: Long) {
    memory.section = SettingsSection.Providers
    memory.openedRow = providerRow(sourceId)
    navigate(Routes.SETTINGS)
}

fun NavGraphBuilder.settingsRoutes(nav: NavController, memory: SettingsMemory) {
    composable(Routes.SETTINGS) {
        SettingsScreen(
            memory = memory,
            onBack = { nav.popBackStack() },
            onAddProvider = { nav.openAddProvider() },
            onEditProvider = { id -> nav.openEditProvider(id) },
            onOpenChannels = { nav.navigate(Routes.CHANNELS) },
            onOpenLockedCategories = { nav.navigate(Routes.LOCKED_CATEGORIES) },
            onOpenFontLicences = { nav.navigate(Routes.FONT_LICENCES) },
            onOpenHomeRows = { nav.navigate(Routes.HOME_ROWS) },
        )
    }

    composable(Routes.HOME_ROWS) {
        HomeRowsScreen(onBack = { nav.popBackStack() })
    }

    composable(Routes.LOCKED_CATEGORIES) {
        LockedCategoriesScreen(onBack = { nav.popBackStack() })
    }

    composable(Routes.FONT_LICENCES) {
        FontLicencesScreen(onBack = { nav.popBackStack() })
    }
}
