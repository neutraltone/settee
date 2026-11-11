/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv.ui.design

import androidx.compose.runtime.Immutable

/** One row of a [CategoriesSidebar]: what the caller knows it by, and what it says. */
@Immutable
class SidebarEntry(val key: String, val label: String)
