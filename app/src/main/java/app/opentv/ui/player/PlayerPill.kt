/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv.ui.player

import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.vector.ImageVector

/** One of the less-used actions that come up under the player's row as pills. */
@Immutable
internal class PlayerPill(val label: String, val icon: ImageVector, val onClick: () -> Unit)
