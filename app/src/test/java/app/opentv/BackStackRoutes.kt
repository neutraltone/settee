/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv

import androidx.navigation.NavController

/** The route of every screen on the back stack, bottom first, for tests that check where Back goes. */
val NavController.backStackRoutes: List<String>
    get() = currentBackStack.value.mapNotNull { it.destination.route }
