/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv.ui.settings

import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import app.opentv.ui.design.ConfirmPanel
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
        note = (status as? AddonsViewModel.Status.Added)?.let { stringResource(R.string.addons_added, it.name) }
            ?: stringResource(R.string.settings_addons_add_note),
    )
    if (addons.isEmpty()) {
        Text(
            stringResource(R.string.addons_empty),
            SetteeType.Body,
            color = SetteeColors.QuietText,
            modifier = Modifier.padding(top = 12.dp),
        )
    }
    TorrentsRow(viewModel)

    details?.let { addon ->
        SidePanel(onBack = { details = null }) {
            PanelTitle(addon.name)
            // Host only: a personalised manifest address can hold a key, which has no place on screen.
            hostOnly(addon.manifestUrl).takeIf { it.isNotEmpty() }?.let {
                Text(it, SetteeType.Body, color = SetteeColors.QuietText)
            }
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
    // Closed only once the add-on is in the list, so a closed window always means it worked; a
    // refusal keeps it open, with what was typed, to fix one character rather than type it all again.
    LaunchedEffect(status) {
        if (status is AddonsViewModel.Status.Added) adding = false
    }
    if (adding) {
        val failed = status as? AddonsViewModel.Status.Failed
        val checking = status == AddonsViewModel.Status.Checking
        EntryWindow(
            title = stringResource(R.string.settings_addons_address),
            initial = "",
            keyboardType = KeyboardType.Uri,
            hint = stringResource(if (checking) R.string.addons_checking else R.string.settings_addons_address_hint),
            onSave = viewModel::add,
            onCancel = {
                adding = false
                viewModel.clearStatus()
            },
            error = failed?.let { it.error.sentence to it.error.detail },
            beside = {
                AddonsFromPhone(onDone = {
                    adding = false
                    viewModel.clearStatus()
                })
            },
        )
    }
}

/**
 * The Torrents switch. Turning it on asks first, since everyone sharing a file can see this box's
 * internet address; the Highlight starts on keeping them off. Turning it off needs no asking.
 */
@Composable
private fun TorrentsRow(viewModel: AddonsViewModel) {
    val on by viewModel.torrentsEnabled.collectAsState()
    var asking by remember { mutableStateOf(false) }
    ListRow(
        stringResource(R.string.settings_addons_torrents),
        onClick = { if (on) viewModel.setTorrentsEnabled(false) else asking = true },
        switch = on,
        note = stringResource(if (on) R.string.settings_addons_torrents_on else R.string.settings_addons_torrents_off),
    )
    if (asking) {
        ConfirmPanel(
            confirm = stringResource(R.string.settings_addons_torrents_confirm),
            keep = stringResource(R.string.settings_addons_torrents_keep),
            onConfirm = {
                asking = false
                viewModel.setTorrentsEnabled(true)
            },
            onKeep = { asking = false },
            title = stringResource(R.string.settings_addons_torrents_title),
            body = stringResource(R.string.settings_addons_torrents_body),
        )
    }
}
