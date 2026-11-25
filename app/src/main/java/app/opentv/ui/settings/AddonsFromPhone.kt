/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.opentv.R
import app.opentv.core.ServiceLocator
import app.opentv.data.repo.AddonRepository
import app.opentv.pairing.PairingServer
import app.opentv.ui.design.SetteeType
import app.opentv.ui.design.Text
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Adding add-ons from a phone, beside the address window: the QR code for the phone's page, and under
 * it each add-on the phone has added while it's open. The phone sends one address at a time; each is
 * checked and kept as a typed one is (`AddonRepository.add`), so the list behind fills as they come,
 * and the phone hears the add-on's name or why it was refused, in the same words. [onDone] hears Done
 * on the phone; leaving the window stops the server.
 */
@Composable
internal fun AddonsFromPhone(onDone: () -> Unit) {
    val graph = ServiceLocator.get(LocalContext.current)
    val server = graph.pairingServer
    val phone by server.state.collectAsState()
    val codeState = codeShown(phone)
    val scope = rememberCoroutineScope()
    var added by remember { mutableStateOf(emptyList<String>()) }
    ScreenKeptOnWhile(codeState is PairingServer.State.Listening)

    // The server is the app's one, so until this window has started it for add-ons its state may be
    // another screen's, which isn't this one's to answer.
    var listening by remember { mutableStateOf(false) }
    LaunchedEffect(phone, listening) {
        if (!listening) return@LaunchedEffect
        when (val now = phone) {
            is PairingServer.State.AddonReceived -> when (val outcome = graph.addonRepository.add(now.address)) {
                is AddonRepository.Outcome.Accepted -> {
                    added = added + outcome.name
                    now.accept(outcome.name)
                }
                is AddonRepository.Outcome.Refused -> now.refuse(graph.errorWording.ofAddon(outcome.failure))
            }
            PairingServer.State.AddonsDone -> onDone()
            // Another screen stopping the server as it went, after this one had started it.
            PairingServer.State.Idle -> server.start(PairingServer.Mode.Addon)
            else -> Unit
        }
    }
    DisposableEffect(Unit) {
        val starting = scope.launch {
            delay(SERVER_DELAY_MS)
            server.start(PairingServer.Mode.Addon)
            listening = true
        }
        onDispose {
            starting.cancel()
            server.stop()
        }
    }

    Column(
        Modifier.width(QR_PANEL_WIDTH).testTag(ADDONS_FROM_PHONE_TAG),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        PhoneQrPanel(
            codeState,
            onRetry = { server.start(PairingServer.Mode.Addon) },
            wording = graph.errorWording,
            description = stringResource(R.string.addons_phone_qr_desc),
        )
        // The latest few: there's room for no more under the code, and the list behind has them all.
        added.takeLast(MAX_LISTED).forEach { name ->
            Text(stringResource(R.string.addons_added, name), SetteeType.Body, maxLines = 1)
        }
    }
}

private const val SERVER_DELAY_MS = 400L
private const val MAX_LISTED = 3

const val ADDONS_FROM_PHONE_TAG = "addons_from_phone"
