/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv.ui.settings

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.viewmodel.compose.viewModel
import app.opentv.R
import app.opentv.core.ErrorWording
import app.opentv.core.ServerProblem
import app.opentv.core.ServiceLocator
import app.opentv.core.ViewerError
import app.opentv.pairing.ManagerServer
import app.opentv.ui.WebManagerViewModel
import app.opentv.ui.design.ListRow
import kotlinx.coroutines.delay

/**
 * Manage on phone or laptop: while this section is open, the TV serves the channel manager on the
 * home network, and the pane shows the QR code and the short address to type. See [ManagerServer]
 * for why it works locally rather than through a website, and for the security model.
 */
@Composable
internal fun ManagerPane(viewModel: WebManagerViewModel = viewModel()) {
    val state by viewModel.state.collectAsState()
    // A moment's wait first, so passing through the section on the way down opens no socket.
    LaunchedEffect(Unit) {
        delay(START_DELAY_MS)
        viewModel.start()
    }
    // Never leave a socket listening on someone's home network once they've left the section.
    DisposableEffect(Unit) { onDispose { viewModel.stop() } }

    when (val current = state) {
        is ManagerServer.State.Listening -> {
            val session = current.session
            CodeBlock(
                lead = stringResource(R.string.settings_manager_scan),
                code = session.shortUrl,
                note = stringResource(R.string.webmanager_local_only),
                beside = { PhoneQrCode(session.url, stringResource(R.string.webmanager_qr_desc)) },
            )
            ListRow(
                stringResource(R.string.settings_manager_new_address),
                onClick = {
                    viewModel.stop()
                    viewModel.start()
                },
                note = stringResource(R.string.settings_manager_new_address_note),
            )
        }
        is ManagerServer.State.Failed -> {
            val wording = ServiceLocator.get(LocalContext.current).errorWording
            val reason = remember(current) { describe(wording, current) }
            ListRow(
                stringResource(R.string.common_try_again),
                onClick = viewModel::start,
                note = reason.sentence,
                detail = reason.detail,
            )
        }
        ManagerServer.State.Idle -> Unit
    }
}

/** What [failed] says to the viewer (docs/ERRORS.md). */
private fun describe(wording: ErrorWording, failed: ManagerServer.State.Failed): ViewerError = when (failed.problem) {
    ServerProblem.NO_NETWORK -> wording.ofHardware(R.string.manager_no_network_tv, R.string.manager_no_network_device)
    ServerProblem.COULDNT_START ->
        wording.ofHardware(R.string.manager_failed_tv, R.string.manager_failed_device, failed.code)
    ServerProblem.TIMED_OUT -> wording.of(R.string.manager_timed_out)
    ServerProblem.TOO_MANY_CODES -> wording.of(R.string.manager_too_many_codes)
}

private const val START_DELAY_MS = 400L
