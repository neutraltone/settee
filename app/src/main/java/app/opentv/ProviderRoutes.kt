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
import app.opentv.ui.hasProvider
import app.opentv.ui.onboarding.AddSourceScreen
import app.opentv.ui.onboarding.TmdbStepScreen
import app.opentv.ui.setup.SetupScreen

/**
 * Opens the page for adding a provider. Every way in (Settings' Providers, Live TV's empty state)
 * comes through here to the one page first run opens on, so none of them grows a variant of it.
 */
fun NavController.openAddProvider() = navigate(Routes.ADD_SOURCE)

/**
 * The screens for adding and editing providers. Kept apart from the rest of the graph so
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
            // The first provider's Setup is already loading; while there's no TMDB Key, the TMDB
            // step comes first, in front of it.
            onSetup = { nav.navigate(if (sources.offersTmdbKey()) Routes.TMDB_STEP else Routes.SETUP) },
            offerTmdbKey = sources::offersTmdbKey,
        )
    }

    composable(Routes.TMDB_STEP) {
        val setup by sources.setup.collectAsState()
        // A failed login goes back to the form, which says why, as from Setup itself.
        LaunchedEffect(setup?.loginFailed) { if (setup?.loginFailed == true) nav.popBackStack() }
        TmdbStepScreen(
            graph = sources.graph,
            onDone = { nav.navigate(Routes.SETUP) { popUpTo(Routes.TMDB_STEP) { inclusive = true } } },
        )
    }

    composable(Routes.SETUP) {
        val setup by sources.setup.collectAsState()
        val liveTv by sources.liveEnabled.collectAsState()
        val ui by sources.ui.collectAsState()
        val current = setup
        if (current == null) {
            // Android restored the app onto Setup after killing it, so nothing is running: Setup
            // doesn't resume. With the channels in it's Home, otherwise the provider choice again.
            LaunchedEffect(ui.loaded) {
                if (!ui.loaded) return@LaunchedEffect
                if (hasProvider(ui.sources)) {
                    nav.leaveSetup(Tab.HOME)
                } else {
                    nav.navigate(Routes.ADD_SOURCE) { popUpTo(nav.graph.id) { inclusive = true } }
                }
            }
            return@composable
        }
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
}
