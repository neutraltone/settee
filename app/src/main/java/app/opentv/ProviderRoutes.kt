/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavController
import androidx.navigation.NavGraphBuilder
import androidx.navigation.compose.composable
import app.opentv.ui.SourcesViewModel
import app.opentv.ui.Tab
import app.opentv.ui.hasProvider
import app.opentv.ui.onboarding.AddSourceScreen
import app.opentv.ui.onboarding.CrashReportsStepScreen
import app.opentv.ui.onboarding.OpenSubtitlesStepScreen
import app.opentv.ui.onboarding.offersOpenSubtitles
import app.opentv.ui.setup.SetupScreen

/**
 * Opens the page for adding a provider. Every way in (Settings' Providers, Live TV's empty state)
 * comes through here to the one page first run opens on, so none of them grows a variant of it.
 */
fun NavController.openAddProvider() = navigate(Routes.ADD_SOURCE)

/** A provider's own form, from Settings or from Movies or Shows that failed to download. */
fun NavController.openEditProvider(sourceId: Long) = navigate(Routes.editSource(sourceId))

/**
 * The screens for adding and editing providers. Kept apart from the rest of the graph so
 * a test can build them with the real screens and check where each Add leads.
 */
fun NavGraphBuilder.providerRoutes(nav: NavController, sources: SourcesViewModel) {
    composable(Routes.ADD_SOURCE) { entry ->
        LeavesOnceSaved(sources, nav, entry, isMine = { it.added }) { leaveAddPage() }
        AddSourceScreen(
            viewModel = sources,
            onFinished = { nav.ifStillOn(entry) { leaveAddPage() } },
            // The first provider's Setup is already loading; OpenSubtitles' and Crash Reports' steps,
            // when there are any, come first, in front of it. Not once the viewer has left: the save
            // carries on without it.
            onSetup = { nav.ifStillOn(entry) { navigate(firstStep(sources)) } },
        )
    }

    openSubtitlesStep(nav, sources)
    crashReportsStep(nav, sources)

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
        LeavesOnceSaved(sources, nav, entry, isMine = { !it.added && it.sourceId == sourceId }) { popBackStack() }
        AddSourceScreen(
            viewModel = sources,
            editingSourceId = sourceId,
            onFinished = { nav.ifStillOn(entry) { popBackStack() } },
        )
    }
}

/**
 * Back to the screen the add page was opened from, as a screen opened from a row returns to it
 * (LOOK.md). Pushing Home instead would leave a second main screen over the first. Home only when
 * the page has nothing under it.
 */
private fun NavController.leaveAddPage() {
    if (previousBackStackEntry != null) {
        popBackStack()
    } else {
        navigate(Routes.MAIN) { popUpTo(Routes.ADD_SOURCE) { inclusive = true } }
    }
}

/**
 * Leaves the provider page shown by [entry], by [leave], once a save [isMine] has its channels in.
 * Watched here rather than handed to the save as a callback, which would hold the page and
 * NavController it started on: a page opened again while the save ran, or rebuilt by a phone
 * turning round, would never leave. Only while [entry] is on top, so a save that finishes after the
 * viewer has gone elsewhere takes them nowhere.
 */
@Composable
private fun LeavesOnceSaved(
    sources: SourcesViewModel,
    nav: NavController,
    entry: NavBackStackEntry,
    isMine: (SourcesViewModel.SavedProvider) -> Boolean,
    leave: NavController.() -> Unit,
) {
    // Saveable, so only a page newly opened forgets a save that finished before it: that one isn't
    // its to act on. A page rebuilt by a phone turning round still acts on the save it started.
    var opened by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        if (!opened) sources.forgetSaved()
        opened = true
        sources.ui.collect { ui ->
            val saved = ui.saved
            if (saved != null && isMine(saved)) {
                sources.forgetSaved()
                nav.ifStillOn(entry, leave)
            }
        }
    }
}

/** Where a first provider goes once saved: OpenSubtitles' step when there is one, then the rest. */
private fun firstStep(sources: SourcesViewModel): String =
    if (sources.graph.offersOpenSubtitles()) Routes.OPENSUBTITLES_STEP else afterOpenSubtitles(sources)

/** Where Setup goes after OpenSubtitles' step: Crash Reports' step when there is one, then Setup. */
private fun afterOpenSubtitles(sources: SourcesViewModel): String =
    if (sources.graph.crashReports.offersSetupStep) Routes.CRASH_REPORTS_STEP else Routes.SETUP

/**
 * Back from one of Setup's steps: to the form the provider was saved from, still filled in, which is
 * why it stays under the steps. The save is taken back, or the form would show a provider already
 * loading, and saving it again would add a second.
 */
private fun NavController.backToForm(sources: SourcesViewModel) {
    sources.undoSetup()
    popBackStack()
}

/** Setup's optional OpenSubtitles step. */
private fun NavGraphBuilder.openSubtitlesStep(nav: NavController, sources: SourcesViewModel) {
    composable(Routes.OPENSUBTITLES_STEP) {
        val setup by sources.setup.collectAsState()
        // A failed login goes back to the form, which says why, as from Setup itself.
        LaunchedEffect(setup?.loginFailed) { if (setup?.loginFailed == true) nav.popBackStack() }
        val openSubtitles = sources.graph.openSubtitles
        // Off the back stack as it goes, so Back from the next step goes to the form: rebuilt, this
        // step would offer a sign-in that may already have happened.
        val goOn = {
            nav.navigate(afterOpenSubtitles(sources)) { popUpTo(Routes.OPENSUBTITLES_STEP) { inclusive = true } }
        }
        if (openSubtitles == null) {
            LaunchedEffect(Unit) { goOn() }
        } else {
            OpenSubtitlesStepScreen(
                sources.graph,
                openSubtitles,
                onDone = goOn,
                onBack = { nav.backToForm(sources) },
            )
        }
    }
}

/** Setup's Crash Reports step, offered only by a build with Settee's Sentry DSN. */
private fun NavGraphBuilder.crashReportsStep(nav: NavController, sources: SourcesViewModel) {
    composable(Routes.CRASH_REPORTS_STEP) {
        val setup by sources.setup.collectAsState()
        LaunchedEffect(setup?.loginFailed) { if (setup?.loginFailed == true) nav.popBackStack() }
        CrashReportsStepScreen(
            sources.graph.crashReports,
            onDone = { nav.navigate(Routes.SETUP) { popUpTo(Routes.CRASH_REPORTS_STEP) { inclusive = true } } },
            onBack = { nav.backToForm(sources) },
        )
    }
}
