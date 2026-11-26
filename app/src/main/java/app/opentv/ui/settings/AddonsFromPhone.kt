/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
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
import app.opentv.ui.design.ActionButton
import app.opentv.ui.design.SetteeType
import app.opentv.ui.design.Text
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Adding add-ons from a phone, beside the address window: the QR code for the phone's page, and under
 * it each add-on the phone has added while it's open. The phone sends one address at a time; each is
 * checked and kept as a typed one is (`AddonRepository.add`), so the list behind fills as they come,
 * and the phone hears the add-on's name or why it was refused, in the same words. [onDone] hears Done,
 * on the phone or under the code here; leaving the window stops the server.
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

    Column(Modifier.width(QR_PANEL_WIDTH).fillMaxHeight().testTag(ADDONS_FROM_PHONE_TAG)) {
        PhoneQrPanel(
            codeState,
            onRetry = { server.start(PairingServer.Mode.Addon) },
            wording = graph.errorWording,
            description = stringResource(R.string.addons_phone_qr_desc),
            // Smaller than the pairing screen's: a TV screen is 540 dp tall, and under the code go Done and
            // every add-on the phone adds.
            codeSize = ADDONS_QR_SIZE,
        )
        // Above the list rather than under it, so it stays put however many come: Down from Save
        // finds it in the same place each time.
        ActionButton(stringResource(R.string.common_done), onClick = onDone)
        Spacer(Modifier.height(LIST_GAP))
        AddedList(added, Modifier.weight(1f))
    }
}

/**
 * Every add-on the phone has added, oldest first, in the room left under the code. Its rows take no
 * Highlight, so it follows the newest rather than wait to be scrolled: the list behind has them all.
 */
@Composable
private fun AddedList(added: List<String>, modifier: Modifier) {
    val list = rememberLazyListState()
    LaunchedEffect(added.size) { if (added.isNotEmpty()) list.animateScrollToItem(added.lastIndex) }
    LazyColumn(modifier, state = list, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        items(added) { name -> Text(stringResource(R.string.addons_added, name), SetteeType.Body, maxLines = 1) }
    }
}

private const val SERVER_DELAY_MS = 400L
private val LIST_GAP = 12.dp
private val ADDONS_QR_SIZE = 200.dp

const val ADDONS_FROM_PHONE_TAG = "addons_from_phone"
