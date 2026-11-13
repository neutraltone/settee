/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv.ui

import androidx.activity.ComponentActivity
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.viewmodel.compose.LocalViewModelStoreOwner
import app.opentv.core.findActivity

/**
 * The activity, as the owner of the one [VodViewModel] that Movies, Shows, Search and the title pages
 * share. Asked for where each screen defaults its model, rather than handed down through MainScreen:
 * the default on a back-stack entry built one model per screen, each scanning the whole library
 * (#258). Falls back to the nearest owner where there's no activity.
 */
@Composable
fun activityViewModelStoreOwner(): ViewModelStoreOwner =
    LocalContext.current.findActivity() as? ComponentActivity
        ?: checkNotNull(LocalViewModelStoreOwner.current) { "No ViewModelStoreOwner here" }
