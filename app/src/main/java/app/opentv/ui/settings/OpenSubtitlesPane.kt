/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import app.opentv.R
import app.opentv.core.ErrorWording
import app.opentv.core.ServiceLocator
import app.opentv.core.ViewerError
import app.opentv.data.repo.OpenSubtitles
import app.opentv.data.repo.OpenSubtitlesAccount
import app.opentv.pairing.PairingServer
import app.opentv.ui.design.ErrorMessage
import app.opentv.ui.design.ListRow
import app.opentv.ui.design.SetteeColors
import app.opentv.ui.design.SetteeType
import app.opentv.ui.design.Text
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Settings › OpenSubtitles: whether the box is signed in to an OpenSubtitles Account and how many
 * downloads it has left today, Sign in / Sign out, the automatic-download switch, and the
 * attribution OpenSubtitles asks for. Only in a build with Settee's key.
 */
@Composable
internal fun OpenSubtitlesPane(openSubtitles: OpenSubtitles) {
    val graph = ServiceLocator.get(LocalContext.current)
    val account = openSubtitles.account
    val signedInAs by account.signedInAs.collectAsState()
    val auto by account.autoDownload.collectAsState()
    Column(Modifier.testTag(OPENSUBTITLES_PANE_TAG), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        val name = signedInAs
        if (name != null) {
            val left by produceState<Int?>(null, name) { value = account.allowance().getOrNull()?.remaining }
            CodeBlock(
                lead = stringResource(R.string.opensubtitles_signed_in_as, name),
                code = left?.let { stringResource(R.string.opensubtitles_downloads_left, it) }.orEmpty(),
            )
            ListRow(stringResource(R.string.opensubtitles_sign_out), onClick = account::signOut)
        } else {
            Text(stringResource(R.string.opensubtitles_not_signed_in), SetteeType.Body, color = SetteeColors.QuietText)
            OpenSubtitlesSignInRows(account, onboarding = false, graph = graph)
        }
        ListRow(
            stringResource(R.string.opensubtitles_auto),
            onClick = { account.setAutoDownload(!auto) },
            note = stringResource(R.string.opensubtitles_auto_detail),
            switch = auto,
        )
        OpenSubtitlesAttribution(Modifier.padding(top = 12.dp))
    }
}

/** The line OpenSubtitles' terms ask for wherever subtitles are found or picked. */
@Composable
internal fun OpenSubtitlesAttribution(modifier: Modifier = Modifier) {
    Text(
        stringResource(R.string.opensubtitles_attribution),
        SetteeType.Body,
        color = SetteeColors.QuietText,
        modifier = modifier,
    )
}

/** Where signing in has got to: signing in, and why the last try failed. */
@Stable
internal class OpenSubtitlesSignIn(private val account: OpenSubtitlesAccount, private val wording: ErrorWording) {
    var signingIn by mutableStateOf(false)
        private set
    var problem by mutableStateOf<ViewerError?>(null)
        private set

    /** Signs in; why not, or null once signed in. */
    suspend fun signIn(username: String, password: String): ViewerError? {
        signingIn = true
        problem = null
        val why = account.signIn(username, password).exceptionOrNull()?.let(wording::ofOpenSubtitles)
        signingIn = false
        problem = why
        return why
    }
}

/**
 * The ways to sign in, shared by Settings › OpenSubtitles and Setup's step: the QR code for the
 * phone's form ([code], laid out by the caller) and Sign in on this TV, which asks for the username
 * then the password with the TV's keyboard, then a line for signing in and why it failed.
 * [onSignedIn] hears a sign-in that worked; [onSkipped], Not now on the phone during Setup.
 */
@Composable
internal fun OpenSubtitlesSignInRows(
    account: OpenSubtitlesAccount,
    onboarding: Boolean,
    graph: ServiceLocator.Graph,
    onSignedIn: () -> Unit = {},
    onSkipped: () -> Unit = {},
    arrange: @Composable (code: @Composable (panel: Boolean) -> Unit, rows: @Composable () -> Unit) -> Unit =
        { code, rows ->
            code(false)
            rows()
        },
) {
    val entry = remember(account, graph) { OpenSubtitlesSignIn(account, graph.errorWording) }
    val server = graph.pairingServer
    val phone by server.state.collectAsState()
    val codeState = codeShown(phone)
    val scope = rememberCoroutineScope()
    var askingUsername by remember { mutableStateOf(false) }
    var username by remember { mutableStateOf<String?>(null) }
    ScreenKeptOnWhile(codeState is PairingServer.State.Listening)

    suspend fun signIn(name: String, password: String): ViewerError? =
        entry.signIn(name, password).also { why -> if (why == null) onSignedIn() }

    // The server is the app's one, so until this screen has started it for OpenSubtitles its state may
    // be the screen before's: Setup's TMDB step skipped from the phone leaves a skip this step isn't
    // to take as its own Not now.
    var listening by remember { mutableStateOf(false) }
    LaunchedEffect(phone, listening) {
        if (!listening) return@LaunchedEffect
        when (val now = phone) {
            is PairingServer.State.SignInReceived -> now.answer(signIn(now.username, now.password))
            PairingServer.State.KeySkipped -> if (onboarding) onSkipped()
            // The screen before stopping the server as it went, after this one had started it.
            PairingServer.State.Idle -> server.start(PairingServer.Mode.OpenSubtitles(onboarding))
            else -> Unit
        }
    }
    DisposableEffect(Unit) {
        val starting = scope.launch {
            delay(SERVER_DELAY_MS)
            server.start(PairingServer.Mode.OpenSubtitles(onboarding))
            listening = true
        }
        onDispose {
            starting.cancel()
            server.stop()
        }
    }

    val retry = { server.start(PairingServer.Mode.OpenSubtitles(onboarding)) }
    arrange(
        { panel ->
            if (panel) {
                PhoneQrPanel(codeState, retry, graph.errorWording)
            } else if (codeState is PairingServer.State.Listening) {
                CodeBlock(
                    lead = stringResource(R.string.opensubtitles_sign_in_phone),
                    code = codeState.session.shortUrl,
                    note = stringResource(R.string.webmanager_local_only),
                    beside = {
                        PhoneQrCode(
                            codeState.session.url,
                            stringResource(R.string.settings_tmdb_qr_desc),
                            SIGN_IN_QR_SIZE,
                        )
                    },
                )
            }
        },
        {
            ListRow(
                stringResource(R.string.opensubtitles_sign_in_here),
                onClick = { askingUsername = true },
                note = stringResource(R.string.opensubtitles_create_account),
                opens = true,
                modifier = Modifier.testTag(OPENSUBTITLES_SIGN_IN_HERE_TAG),
            )
            SignInStatus(entry.signingIn, entry.problem)
        },
    )

    if (askingUsername) {
        EntryWindow(
            title = stringResource(R.string.opensubtitles_username),
            initial = username.orEmpty(),
            onSave = {
                askingUsername = false
                username = it.trim()
            },
            onCancel = { askingUsername = false },
        )
    }
    username?.let { name ->
        PasswordWindow(
            onSave = { password ->
                username = null
                scope.launch { signIn(name, password) }
            },
            onCancel = { username = null },
        )
    }
}

/** Signing in, or why the last try failed, in space that's always there so nothing below moves. */
@Composable
private fun SignInStatus(signingIn: Boolean, problem: ViewerError?) =
    Box(Modifier.fillMaxWidth().heightIn(min = STATUS_HEIGHT), contentAlignment = Alignment.TopStart) {
        if (signingIn) {
            Text(
                stringResource(R.string.opensubtitles_signing_in),
                SetteeType.Body,
                color = SetteeColors.QuietText,
                modifier = Modifier.padding(top = 12.dp),
            )
        } else {
            problem?.let {
                ErrorMessage(it.sentence, it.detail, Modifier.padding(top = 12.dp), textAlign = TextAlign.Start)
            }
        }
    }

/** The password, asked for after the username; never filled in, and typed with the keyboard's password mode. */
@Composable
private fun PasswordWindow(onSave: (String) -> Unit, onCancel: () -> Unit) = EntryWindow(
    title = stringResource(R.string.opensubtitles_password),
    initial = "",
    keyboardType = KeyboardType.Password,
    onSave = onSave,
    onCancel = onCancel,
)

private const val SERVER_DELAY_MS = 400L
private val SIGN_IN_QR_SIZE = 140.dp
private val STATUS_HEIGHT = 88.dp

const val OPENSUBTITLES_PANE_TAG = "opensubtitles_pane"
const val OPENSUBTITLES_SIGN_IN_HERE_TAG = "opensubtitles_sign_in_here"
