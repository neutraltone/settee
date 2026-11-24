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
import app.opentv.data.remote.OpenSubtitlesFailure
import app.opentv.data.remote.OpenSubtitlesSession
import app.opentv.data.repo.OpenSubtitles
import app.opentv.data.repo.OpenSubtitlesAccount
import app.opentv.pairing.PairingServer
import app.opentv.ui.design.ErrorMessage
import app.opentv.ui.design.ListRow
import app.opentv.ui.design.SetteeColors
import app.opentv.ui.design.SetteeType
import app.opentv.ui.design.Text
import app.opentv.ui.sendReportFor
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
    // One per account signed in, so signing out and in again never shows the last one's check or error.
    val entry = remember(account, graph, signedInAs) { OpenSubtitlesSignIn(account, graph.errorWording) }
    val scope = rememberCoroutineScope()
    var signingInAgainAs by remember { mutableStateOf<String?>(null) }
    Column(Modifier.testTag(OPENSUBTITLES_PANE_TAG), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        val name = signedInAs
        if (name != null) {
            LaunchedEffect(name) { entry.checkAllowance() }
            val left = entry.allowance?.getOrNull()?.remaining
            CodeBlock(
                lead = stringResource(R.string.opensubtitles_signed_in_as, name),
                // Room kept while checking, so the number arriving moves nothing; none once the error stands in for it.
                code = left?.let { stringResource(R.string.opensubtitles_downloads_left, it) }
                    ?: "".takeIf { entry.accountProblem == null },
            )
            entry.accountProblem?.let {
                ErrorMessage(it.sentence, it.detail, textAlign = TextAlign.Start)
            }
            if (entry.offersSignInAgain) {
                ListRow(
                    stringResource(R.string.opensubtitles_sign_in_again),
                    onClick = { signingInAgainAs = name },
                    opens = true,
                )
                SignInStatus(entry.signingIn, entry.problem)
            }
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
    SignInWindows(
        asking = signingInAgainAs,
        onAsked = { signingInAgainAs = null },
        onSignIn = { username, password -> scope.launch { entry.signIn(username, password) } },
    )
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

/**
 * Where signing in has got to (signing in, and why the last try failed) and, once signed in, what the
 * account's allowance check last said.
 */
@Stable
internal class OpenSubtitlesSignIn(private val account: OpenSubtitlesAccount, private val wording: ErrorWording) {
    var signingIn by mutableStateOf(false)
        private set
    var problem by mutableStateOf<ViewerError?>(null)
        private set

    /** The last allowance check, the failure kept as well as the number; null until one has finished. */
    var allowance by mutableStateOf<Result<OpenSubtitlesSession>?>(null)
        private set

    /** Why the last allowance check failed, worded; null when it worked or hasn't finished. */
    val accountProblem: ViewerError? get() = allowance?.exceptionOrNull()?.let(wording::ofOpenSubtitles)

    /**
     * Whether OpenSubtitles has signed this box out: it turned down the token and then the saved password.
     * The account stays as it is (see [OpenSubtitlesAccount]), so only signing in again mends it.
     */
    val offersSignInAgain: Boolean get() = allowance?.exceptionOrNull() is OpenSubtitlesFailure.TokenRejected

    suspend fun checkAllowance() {
        allowance = account.allowance()
    }

    /** Signs in; why not, or null once signed in. */
    suspend fun signIn(username: String, password: String): ViewerError? {
        signingIn = true
        problem = null
        val signedIn = account.signIn(username, password)
        val why = signedIn.exceptionOrNull()?.let(wording::ofOpenSubtitles)
        // A sign-in's answer carries the allowance, so it stands in for checking it again: signing in again
        // under the same name changes nothing the pane would otherwise re-check on.
        if (signedIn.isSuccess) allowance = signedIn
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
    ScreenKeptOnWhile(codeState is PairingServer.State.Listening)

    suspend fun signIn(name: String, password: String): ViewerError? =
        entry.signIn(name, password).also { why -> if (why == null) onSignedIn() }

    // The server is the app's one, so until this screen has started it for OpenSubtitles its state may
    // be the screen before's, which this step isn't to take as its own answer.
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
                            stringResource(R.string.opensubtitles_qr_desc),
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

    SignInWindows(
        asking = if (askingUsername) "" else null,
        onAsked = { askingUsername = false },
        onSignIn = { name, password -> scope.launch { signIn(name, password) } },
    )
}

/**
 * Sign in on this TV's windows: the username, starting from [asking] while it isn't null, then the
 * password. [onAsked] hears the username window close, either way; [onSignIn], both typed in.
 */
@Composable
private fun SignInWindows(
    asking: String?,
    onAsked: () -> Unit,
    onSignIn: (username: String, password: String) -> Unit,
) {
    var username by remember { mutableStateOf<String?>(null) }
    asking?.let { initial ->
        EntryWindow(
            title = stringResource(R.string.opensubtitles_username),
            initial = initial,
            onSave = {
                onAsked()
                username = it.trim()
            },
            onCancel = onAsked,
        )
    }
    username?.let { name ->
        PasswordWindow(
            onSave = { password ->
                username = null
                onSignIn(name, password)
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
                // Under the sign-in row, so Send report is Down from it. A wrong password never offers it.
                ErrorMessage(
                    it.sentence,
                    it.detail,
                    Modifier.padding(top = 12.dp),
                    textAlign = TextAlign.Start,
                    sendReport = sendReportFor(it, source = null),
                )
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
