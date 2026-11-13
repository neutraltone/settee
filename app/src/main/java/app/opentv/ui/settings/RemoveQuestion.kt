/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv.ui.settings

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import app.opentv.R
import app.opentv.ui.design.ConfirmPanel

/**
 * Asks before removing a provider, Profile, guide source, add-on or the TMDB Key: "Yes, remove <name>"
 * or "Keep it", with [note] above them where what stays needs saying.
 */
@Composable
internal fun RemoveQuestion(name: String, onKeep: () -> Unit, onRemove: () -> Unit, note: String? = null) {
    ConfirmPanel(
        confirm = stringResource(R.string.settings_remove_yes, name),
        keep = stringResource(R.string.settings_remove_keep),
        onConfirm = onRemove,
        onKeep = onKeep,
        body = note,
    )
}
