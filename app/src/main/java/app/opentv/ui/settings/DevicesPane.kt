/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv.ui.settings

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.lifecycle.viewmodel.compose.viewModel
import app.opentv.R
import app.opentv.sync.SyncServer
import app.opentv.ui.SyncViewModel
import app.opentv.ui.design.ListRow

/**
 * Your devices: sharing what's been watched and starred with another device over the home network,
 * getting it from one, and syncing through the viewer's own network drive. While sharing, the
 * address and code to type on the other device show above the rows.
 */
@Composable
internal fun DevicesPane(viewModel: SyncViewModel = viewModel()) {
    val sharing by viewModel.serverState.collectAsState()
    val receiving by viewModel.receiveState.collectAsState()
    val nas by viewModel.nasState.collectAsState()
    val autoSync by viewModel.nasAutoSync.collectAsState()
    val smbHost by viewModel.smbHost.collectAsState()
    var typing by remember { mutableStateOf(false) }

    val state = sharing
    val shareFailure = (state as? SyncServer.State.Failed)?.let { remember(it) { viewModel.describe(it) } }
    if (state is SyncServer.State.Sharing) {
        CodeBlock(
            lead = stringResource(R.string.settings_devices_type_this),
            code = "${state.session.address}#${state.session.code}",
        )
    }
    // One row whose words change, rather than one per state: pressing it swaps Share for Stop
    // sharing, and a new row in its place wouldn't keep the Highlight.
    ListRow(
        stringResource(
            if (state is SyncServer.State.Sharing) R.string.sync_stop_sharing else R.string.settings_devices_share,
        ),
        onClick = if (state is SyncServer.State.Sharing) viewModel::stopSharing else viewModel::startSharing,
        note = when (state) {
            is SyncServer.State.Sharing -> null
            is SyncServer.State.Failed -> shareFailure?.sentence
            SyncServer.State.Idle -> stringResource(R.string.settings_devices_share_note)
        },
        detail = shareFailure?.detail,
    )
    ListRow(
        stringResource(R.string.settings_devices_get),
        onClick = {
            viewModel.resetReceive()
            typing = true
        },
        opens = true,
        note = receiveNote(receiving),
        detail = (receiving as? SyncViewModel.ReceiveState.Failed)?.reason?.detail,
    )
    val nasReady = smbHost.isNotBlank()
    ListRow(
        stringResource(R.string.settings_devices_nas_now),
        onClick = { if (nasReady && nas !is SyncViewModel.NasState.Syncing) viewModel.syncNas() },
        note = if (!nasReady) {
            stringResource(R.string.settings_devices_nas_needs_setup)
        } else {
            nasNote(nas)
        },
        detail = (nas as? SyncViewModel.NasState.Failed)?.reason?.detail.takeIf { nasReady },
    )
    ListRow(
        stringResource(R.string.settings_devices_nas_auto),
        onClick = { viewModel.setNasAutoSync(!autoSync) },
        switch = autoSync,
    )

    if (typing) {
        GetFromAnotherDevice(onCancel = { typing = false }) { address, code ->
            typing = false
            viewModel.receive(address, code)
        }
    }
}

@Composable
private fun receiveNote(state: SyncViewModel.ReceiveState): String? = when (state) {
    SyncViewModel.ReceiveState.Idle -> null
    SyncViewModel.ReceiveState.Connecting -> stringResource(R.string.sync_connecting)
    is SyncViewModel.ReceiveState.Done -> stringResource(R.string.sync_synced_items, state.merged)
    is SyncViewModel.ReceiveState.Failed -> state.reason.sentence
}

@Composable
private fun nasNote(nas: SyncViewModel.NasState): String = when (nas) {
    SyncViewModel.NasState.Idle -> stringResource(R.string.settings_devices_nas_note)
    SyncViewModel.NasState.Syncing -> stringResource(R.string.sync_nas_syncing)
    is SyncViewModel.NasState.Done -> nas.message
    is SyncViewModel.NasState.Failed -> nas.reason.sentence
}

/**
 * The other device shows "address#code"; both are typed in one go. Without the code, the other
 * device would only answer that the code is wrong, so it's asked for here instead.
 */
@Composable
private fun GetFromAnotherDevice(onCancel: () -> Unit, onGet: (address: String, code: String) -> Unit) {
    val needsCode = stringResource(R.string.settings_devices_get_needs_code)
    EntryWindow(
        title = stringResource(R.string.settings_devices_get_title),
        initial = "",
        keyboardType = KeyboardType.Uri,
        hint = stringResource(R.string.settings_devices_get_hint),
        onSave = { typed -> onGet(typed.substringBefore('#').trim(), typed.substringAfter('#', "").trim()) },
        onCancel = onCancel,
        refusal = { typed ->
            if (typed.isNotBlank() && typed.substringAfter('#', "").isBlank()) needsCode else null
        },
    )
}
