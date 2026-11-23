/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv.core

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers

/**
 * The threads the app's background work runs on: [io] for the disk and the network, [default] for
 * working things out. The app always has [Dispatchers.IO] and [Dispatchers.Default]; a
 * [ServiceLocator.Graph] takes others so a test can see when that work has finished (#616), which it
 * can't when repositories name the shared dispatchers themselves.
 */
class AppDispatchers(
    val io: CoroutineDispatcher = Dispatchers.IO,
    val default: CoroutineDispatcher = Dispatchers.Default,
)
