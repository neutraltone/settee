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
import androidx.compose.runtime.Stable
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
import app.opentv.core.ErrorWording
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
 * Where adding the TMDB Key has got to: checking a key, why the last one was turned down, and whether
 * the viewer chose Replace key. Saving and removing go through [keys], so only a key TMDB accepts is kept.
 */
@Stable
private class TmdbKeyEntry(private val keys: TmdbKeys, private val wording: ErrorWording) {
    var replacing by mutableStateOf(false)
    var checking by mutableStateOf(false)
        private set
    var problem by mutableStateOf<ViewerError?>(null)
        private set

    /** Set when the rows the Highlight was on have gone: a key saved or removed swaps them all. */
    var moveHighlight by mutableStateOf(false)

    /** Checks [key] with TMDB, saving it if TMDB accepts it; why not, or null once it's saved. */
    suspend fun save(key: String): ViewerError? {
        checking = true
        problem = null
        val why = wording.ofTmdb(keys.save(key))
        checking = false
        problem = why
        if (why == null) {
            replacing = false
            moveHighlight = true
        }
        return why
    }

    fun remove() {
        keys.remove()
        problem = null
        moveHighlight = true
    }
}

/**
 * What adding the TMDB Key takes, shared by Settings › TMDB and Onboarding's TMDB step. With no key
 * (or Replace key chosen) it's the QR code for the phone's paste box and Type it instead; with one,
 * its last four characters, Replace key and Remove key. The phone's server runs only while the QR
 * code is up. [onSaved] hears each key TMDB accepts; [onTurnedDown] each one it doesn't, with why,
 * which the rows show themselves unless [showProblem] is false.
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
    val entry = remember(check, graph) {
        TmdbKeyEntry(TmdbKeys(graph.settings) { key -> check(context, key) }, graph.errorWording)
    }
    val savedKey by graph.settings.tmdbApiKey.collectAsState()
    val ending = TmdbKeys.endingOf(savedKey)
    val server = graph.pairingServer
    val phone by server.state.collectAsState()
    val scope = rememberCoroutineScope()
    var typing by remember { mutableStateOf(false) }
    var removing by remember { mutableStateOf(false) }
    val replaceRow = remember { FocusRequester() }
    val typeRow = remember { FocusRequester() }
    val asking = ending == null || entry.replacing

    suspend fun save(key: String): ViewerError? = entry.save(key).also { why ->
        if (why == null) onSaved() else onTurnedDown(why)
    }

    // Outside the rows that come and go, so a key arriving from the phone is answered whatever shows.
    LaunchedEffect(phone) {
        val received = phone as? PairingServer.State.KeyReceived ?: return@LaunchedEffect
        received.answer(save(received.key))
    }
    PhoneServerWhile(asking, server, onboarding)
    LaunchedEffect(entry.moveHighlight, asking) {
        if (!entry.moveHighlight) return@LaunchedEffect
        withFrameNanos {}
        runCatching { (if (asking) typeRow else replaceRow).requestFocus() }
        entry.moveHighlight = false
    }

    if (asking) {
        AskingRows(phone, onRetry = { server.start(PairingServer.Mode.TMDB_KEY, onboarding) }, graph.errorWording)
        ListRow(
            stringResource(R.string.settings_tmdb_type),
            onClick = { typing = true },
            modifier = Modifier.focusRequester(typeRow),
            opens = true,
        )
    } else {
        SavedRows(ending.orEmpty(), replaceRow, onReplace = { entry.replacing = true }, onRemove = { removing = true })
    }
    KeyStatus(entry.checking, entry.problem.takeIf { showProblem })

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
                entry.remove()
            },
        )
    }
}

/**
 * The phone's server, only while [asking] shows the QR code: never a socket left listening on the
 * network once it's gone. A moment's wait first, so passing through Settings opens nothing. A server
 * already listening (the first provider's, from the phone) carries on, now for the key.
 */
@Composable
private fun PhoneServerWhile(asking: Boolean, server: PairingServer, onboarding: Boolean) {
    val scope = rememberCoroutineScope()
    DisposableEffect(asking) {
        val starting = if (asking) {
            scope.launch {
                delay(START_DELAY_MS)
                server.start(PairingServer.Mode.TMDB_KEY, onboarding)
            }
        } else {
            null
        }
        onDispose {
            starting?.cancel()
            if (asking) server.stop()
        }
    }
}

/** The QR code for the phone's paste box, or why the server couldn't listen, with Try again. */
@Composable
private fun AskingRows(phone: PairingServer.State, onRetry: () -> Unit, wording: ErrorWording) {
    when (phone) {
        is PairingServer.State.Listening -> {
            val session = phone.session
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
            val reason = remember(phone) { describePairingFailure(wording, phone) }
            ListRow(
                stringResource(R.string.common_try_again),
                onClick = onRetry,
                note = reason.sentence,
                detail = reason.detail,
            )
        }
        else -> Unit
    }
}

/** A saved key: its last four characters, then Replace key and Remove key. */
@Composable
private fun SavedRows(ending: String, replaceRow: FocusRequester, onReplace: () -> Unit, onRemove: () -> Unit) {
    CodeBlock(
        lead = stringResource(R.string.settings_row_tmdb_set),
        code = stringResource(R.string.settings_tmdb_ending, ending),
    )
    ListRow(
        stringResource(R.string.settings_tmdb_replace),
        onClick = onReplace,
        modifier = Modifier.focusRequester(replaceRow),
    )
    ListRow(stringResource(R.string.settings_tmdb_remove), onClick = onRemove)
}

/** Checking a key with TMDB, or why TMDB turned the last one down. */
@Composable
private fun KeyStatus(checking: Boolean, problem: ViewerError?) {
    when {
        checking -> Text(
            stringResource(R.string.settings_tmdb_checking),
            SetteeType.Body,
            color = SetteeColors.QuietText,
            modifier = Modifier.padding(top = 12.dp),
        )
        problem != null ->
            ErrorMessage(problem.sentence, problem.detail, Modifier.padding(top = 12.dp), textAlign = TextAlign.Start)
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
private const val QR_SIZE_PX = 600
private val QR_SIZE = 200.dp
private val QR_PADDING = 10.dp
private val LOGO_HEIGHT = 16.dp
