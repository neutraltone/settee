/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv.ui.settings

import android.content.Context
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import app.opentv.R
import app.opentv.core.ServiceLocator
import app.opentv.core.ViewerError
import app.opentv.data.remote.TmdbCheck
import app.opentv.data.repo.TmdbKeys
import app.opentv.pairing.PairingServer
import app.opentv.pairing.QrCodes
import app.opentv.ui.design.ErrorMessage
import app.opentv.ui.design.ListRow
import app.opentv.ui.design.SetteeColors
import app.opentv.ui.design.SetteeShapes
import app.opentv.ui.design.SetteeType
import app.opentv.ui.design.Text
import app.opentv.ui.onboarding.describePairingFailure
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Asks TMDB whether it takes a TMDB Key. A seam, so tests can give each answer without reaching TMDB. */
typealias TmdbKeyCheck = suspend (Context, String) -> TmdbCheck

internal val CheckTmdb: TmdbKeyCheck = { context, key -> ServiceLocator.get(context).checkTmdbKey(key) }

/** Settings › TMDB: adding the TMDB Key from a phone or by typing it, and replacing or removing it. */
@Composable
internal fun TmdbPane(check: TmdbKeyCheck) {
    TmdbKeyRows(check, onboarding = false)
    TmdbAttribution(Modifier.padding(top = 20.dp))
}

/**
 * What adding the TMDB Key takes, shared by Settings › TMDB and Onboarding's TMDB step. With no key
 * (or Replace key chosen) it's the QR code for the phone's paste box and Type it instead; with one,
 * its last four characters, Replace key and Remove key. The phone's server runs only while the QR
 * code is up. [onSaved] hears each key TMDB accepts; [onTurnedDown] each one it doesn't, with why.
 */
@Composable
internal fun TmdbKeyRows(
    check: TmdbKeyCheck,
    onboarding: Boolean,
    onSaved: () -> Unit = {},
    onTurnedDown: (ViewerError) -> Unit = {},
    showProblem: Boolean = true,
    graph: ServiceLocator.Graph = ServiceLocator.get(LocalContext.current),
) {
    val context = LocalContext.current
    val keys = remember(check, graph) { TmdbKeys(graph.settings) { key -> check(context, key) } }
    val server = graph.pairingServer
    // Started on what's saved, so a saved key never flashes the QR code up first.
    val saved = graph.settings.tmdbApiKey.value.takeLast(ENDING_LENGTH).ifEmpty { null }
    val ending by keys.savedEnding.collectAsState(initial = saved)
    val phone by server.state.collectAsState()
    val scope = rememberCoroutineScope()

    var replacing by remember { mutableStateOf(false) }
    var checking by remember { mutableStateOf(false) }
    var problem by remember { mutableStateOf<ViewerError?>(null) }
    var typing by remember { mutableStateOf(false) }
    var removing by remember { mutableStateOf(false) }
    // Where the Highlight goes when the rows it was on go: a key saved or removed swaps them all.
    var moveHighlight by remember { mutableStateOf(false) }
    val replaceRow = remember { FocusRequester() }
    val typeRow = remember { FocusRequester() }

    val asking = ending == null || replacing

    /** Checks [key] with TMDB, saving it if TMDB accepts it; why not, or null once it's saved. */
    suspend fun save(key: String): ViewerError? {
        checking = true
        problem = null
        val answer = keys.save(key)
        val why = graph.errorWording.ofTmdb(answer)
        checking = false
        problem = why
        if (why == null) {
            replacing = false
            moveHighlight = true
            onSaved()
        } else {
            onTurnedDown(why)
        }
        return why
    }

    // Outside the rows that come and go, so a key arriving from the phone is answered whatever shows.
    LaunchedEffect(phone) {
        val received = phone as? PairingServer.State.KeyReceived ?: return@LaunchedEffect
        received.answer(save(received.key))
    }
    // The QR code's server, only while the QR code is up: never a socket left listening on the
    // network once it's gone. A moment's wait first, so passing through Settings opens nothing. A
    // server already listening (the first provider's, from the phone) carries on, now for the key.
    DisposableEffect(asking) {
        val starting = if (asking) {
            scope.launch {
                delay(START_DELAY_MS)
                server.start(PairingServer.Mode.TMDB_KEY, onboarding = onboarding)
            }
        } else {
            null
        }
        onDispose {
            starting?.cancel()
            if (asking) server.stop()
        }
    }
    LaunchedEffect(moveHighlight, asking) {
        if (!moveHighlight) return@LaunchedEffect
        withFrameNanos {}
        runCatching { (if (asking) typeRow else replaceRow).requestFocus() }
        moveHighlight = false
    }

    if (asking) {
        when (val current = phone) {
            is PairingServer.State.Listening -> {
                val session = current.session
                // Drawn once per address: encoding isn't free, and the content doesn't change.
                val qr = remember(session.url) { QrCodes.render(session.url, QR_SIZE_PX) }
                CodeBlock(
                    lead = stringResource(R.string.settings_tmdb_scan),
                    code = session.shortUrl,
                    note = stringResource(R.string.webmanager_local_only),
                    beside = {
                        qr?.let {
                            Image(
                                it.asImageBitmap(),
                                contentDescription = stringResource(R.string.settings_tmdb_qr_desc),
                                modifier = Modifier
                                    .size(QR_SIZE)
                                    .background(Color.White, SetteeShapes.Card)
                                    .padding(QR_PADDING),
                            )
                        }
                    },
                )
            }
            is PairingServer.State.Failed -> {
                val reason = remember(current) { describePairingFailure(graph.errorWording, current) }
                ListRow(
                    stringResource(R.string.common_try_again),
                    onClick = { server.start(PairingServer.Mode.TMDB_KEY, onboarding = onboarding) },
                    note = reason.sentence,
                    detail = reason.detail,
                )
            }
            else -> Unit
        }
        ListRow(
            stringResource(R.string.settings_tmdb_type),
            onClick = { typing = true },
            modifier = Modifier.focusRequester(typeRow),
            opens = true,
        )
    } else {
        CodeBlock(
            lead = stringResource(R.string.settings_row_tmdb_set),
            code = stringResource(R.string.settings_tmdb_ending, ending.orEmpty()),
        )
        ListRow(
            stringResource(R.string.settings_tmdb_replace),
            onClick = { replacing = true },
            modifier = Modifier.focusRequester(replaceRow),
        )
        ListRow(stringResource(R.string.settings_tmdb_remove), onClick = { removing = true })
    }

    if (checking) {
        Text(
            stringResource(R.string.settings_tmdb_checking),
            SetteeType.Body,
            color = SetteeColors.QuietText,
            modifier = Modifier.padding(top = 12.dp),
        )
    }
    val why = problem
    if (why != null && showProblem && !checking) {
        ErrorMessage(why.sentence, why.detail, Modifier.padding(top = 12.dp), textAlign = TextAlign.Start)
    }

    if (typing) {
        // Never filled in with the saved key: the pane only ever shows its last four characters.
        EntryWindow(
            title = stringResource(R.string.settings_tmdb_key_label),
            initial = "",
            hint = stringResource(R.string.settings_tmdb_type_hint),
            onSave = { key ->
                typing = false
                scope.launch { save(key) }
            },
            onCancel = { typing = false },
        )
    }
    if (removing) {
        RemoveQuestion(
            stringResource(R.string.settings_tmdb_remove_name),
            onKeep = { removing = false },
            onRemove = {
                removing = false
                keys.remove()
                problem = null
                moveHighlight = true
            },
        )
    }
}

/** TMDB's logo and the sentence its terms ask for, in Settings › TMDB and About. */
@Composable
internal fun TmdbAttribution(modifier: Modifier = Modifier) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        // Smaller than Settee's own mark anywhere, as TMDB's terms ask.
        Image(
            painterResource(R.drawable.tmdb_logo),
            contentDescription = stringResource(R.string.tmdb_logo_desc),
            modifier = Modifier.height(LOGO_HEIGHT),
        )
        Column(Modifier.padding(start = 16.dp)) {
            Text(stringResource(R.string.tmdb_attribution), SetteeType.Body, color = SetteeColors.QuietText)
        }
    }
}

private const val START_DELAY_MS = 400L
private const val ENDING_LENGTH = 4
private const val QR_SIZE_PX = 600
private val QR_SIZE = 200.dp
private val QR_PADDING = 10.dp
private val LOGO_HEIGHT = 16.dp
