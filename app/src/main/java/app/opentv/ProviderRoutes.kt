/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv

import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.navigation.NavController
import androidx.navigation.NavGraphBuilder
import androidx.navigation.compose.composable
import app.opentv.ui.SourcesViewModel
import app.opentv.ui.Tab
import app.opentv.ui.onboarding.AddSourceScreen
import app.opentv.ui.settings.ProvidersScreen
import app.opentv.ui.setup.SetupScreen

/**
 * Opens the page for adding a provider. Every way in (Settings' Providers, Live TV's empty state)
 * comes through here to the one page first run opens on, so none of them grows a variant of it.
 */
fun NavController.openAddProvider() = navigate(Routes.ADD_SOURCE)

/**
 * The screens for adding, editing and listing providers. Kept apart from the rest of the graph so
 * a test can build them with the real screens and check where each Add leads.
 */
fun NavGraphBuilder.providerRoutes(nav: NavController, sources: SourcesViewModel) {
    composable(Routes.ADD_SOURCE) {
        AddSourceScreen(
            viewModel = sources,
            onFinished = {
                nav.navigate(Routes.MAIN) {
                    popUpTo(Routes.ADD_SOURCE) { inclusive = true }
                }
            },
            onSetup = { nav.navigate(Routes.SETUP) },
        )
    }

    composable(Routes.SETUP) {
        val setup by sources.setup.collectAsState()
        val liveTv by sources.liveEnabled.collectAsState()
        val current = setup ?: return@composable
        // Back to the form, which says why the login failed.
        LaunchedEffect(current.loginFailed) { if (current.loginFailed) nav.popBackStack() }
        SetupScreen(
            setup = current,
            onWatchLive = if (liveTv) ({ nav.leaveSetup(Tab.LIVE) }) else null,
            onFinish = { nav.leaveSetup(Tab.HOME) },
        )
    }

    composable(Routes.EDIT_SOURCE) { entry ->
        val sourceId = entry.arguments?.getString("sourceId")?.toLongOrNull() ?: return@composable
        AddSourceScreen(
            viewModel = sources,
            editingSourceId = sourceId,
            onFinished = { nav.popBackStack() },
        )
    }

    composable(Routes.PROVIDERS) {
        ProvidersScreen(
            viewModel = sources,
            onAddSource = { nav.openAddProvider() },
            onEditSource = { src -> nav.navigate(Routes.editSource(src.id)) },
            onBack = { nav.popBackStack() },
        )
    }
}
