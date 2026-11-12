/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv.ui.settings

import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import app.opentv.R
import app.opentv.data.model.StremioAddon
import app.opentv.ui.design.ListRow
import app.opentv.ui.design.PanelRow
import app.opentv.ui.design.PanelTitle
import app.opentv.ui.design.SetteeColors
import app.opentv.ui.design.SetteeType
import app.opentv.ui.design.SidePanel
import app.opentv.ui.design.Text

/**
 * Add-ons: a row per Stremio add-on, whose details hold where it's from and Remove, and a row to
 * add one by its address. Settee ships none and points at none; the viewer brings their own.
 */
@Composable
internal fun AddonsPane(viewModel: AddonsViewModel = viewModel()) {
    val addons by viewModel.addons.collectAsState()
    val status by viewModel.status.collectAsState()
    var details by remember { mutableStateOf<StremioAddon?>(null) }
    var removing by remember { mutableStateOf<StremioAddon?>(null) }
    var adding by remember { mutableStateOf(false) }

    addons.forEach { addon ->
        key(addon.manifestUrl) {
            ListRow(
                addon.name,
                onClick = { details = addon },
                note = hostOnly(addon.manifestUrl),
                onOptions = { details = addon },
            )
        }
    }
    ListRow(
        stringResource(R.string.settings_addons_add),
        onClick = {
            viewModel.clearStatus()
            adding = true
        },
        opens = true,
        note = when (val current = status) {
            AddonsViewModel.Status.Idle -> stringResource(R.string.settings_addons_add_note)
            AddonsViewModel.Status.Checking -> stringResource(R.string.addons_checking)
            is AddonsViewModel.Status.Added -> stringResource(R.string.addons_added, current.name)
            AddonsViewModel.Status.Invalid -> stringResource(R.string.settings_addons_invalid)
        },
    )
    if (addons.isEmpty()) {
        Text(
            stringResource(R.string.settings_addons_empty),
            SetteeType.Body,
            color = SetteeColors.QuietText,
            modifier = Modifier.padding(top = 12.dp),
        )
    }

    details?.let { addon ->
        SidePanel(onBack = { details = null }) {
            PanelTitle(addon.name)
            // Host only: a personalised manifest address can hold a key, which has no place on screen.
            Text(hostOnly(addon.manifestUrl), SetteeType.Body, color = SetteeColors.QuietText)
            PanelRow(stringResource(R.string.common_remove), onClick = {
                details = null
                removing = addon
            })
        }
    }
    removing?.let { addon ->
        RemoveQuestion(addon.name, onKeep = { removing = null }, onRemove = {
            removing = null
            viewModel.remove(addon)
        })
    }
    if (adding) {
        EntryWindow(
            title = stringResource(R.string.settings_addons_address),
            initial = "",
            keyboardType = KeyboardType.Uri,
            hint = stringResource(R.string.settings_addons_address_hint),
            onSave = { url ->
                adding = false
                viewModel.add(url)
            },
            onCancel = { adding = false },
        )
    }
}
