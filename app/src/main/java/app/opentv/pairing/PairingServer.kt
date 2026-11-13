/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv.pairing

import android.util.Log
import app.opentv.R
import app.opentv.core.ServerProblem
import app.opentv.core.ViewerError
import app.opentv.data.model.Source
import app.opentv.data.model.SourceKind
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.Socket
import java.security.SecureRandom
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Lets someone fill in their provider details from a phone instead of a TV remote.
 *
 * ## Why the TV is the server
 *
 * The obvious way to build "scan a code, type on your phone, it appears on the telly" is a
 * website: the TV registers a code with a server, the phone posts to the same server, the TV
 * polls for it. Every streaming app does it that way.
 *
 * It is the wrong choice here. That server has to be paid for every month forever, and it
 * would hold IPTV credentials for every user of the app. It is precisely the dependency that
 * means an app dies when its maintainer loses interest — which is the failure this whole
 * project exists in response to.
 *
 * So there is no server. The TV runs one, on the local network, for the couple of minutes the
 * pairing screen is open. Credentials go phone → TV over the user's own wifi and never leave
 * the building. It works with the internet unplugged.
 *
 * ## What stops the neighbours using it
 *
 * - The address, its port and both tokens come from [PhoneAddress], shared with [ManagerServer]:
 *   a **random high port** on the LAN, open only while the pairing screen is, a QR code carrying
 *   a **192-bit random token**, and a typeable address with a **seven-character short token**
 *   that switches itself off after [PhoneAddress.MAX_SHORT_TOKEN_GUESSES] wrong guesses. Its
 *   comments say why each is enough. Here the typed address only reaches the form; the code
 *   below is still needed to send anything.
 * - The phone must also enter a **six-digit code shown on the TV**. That is the part the user
 *   sees, and it is what defends against someone who somehow obtained the link.
 * - Requests from non-private addresses are refused outright.
 * - After [SESSION_TIMEOUT_MINUTES], or on success, the socket closes and the token is void.
 * - Five wrong codes and the session is burned.
 *
 * ## The TMDB Key
 *
 * The same server takes the viewer's TMDB Key ([Mode.TmdbKey]), from Settings › TMDB, Onboarding's
 * TMDB step, or straight on from the first provider ([Mode.Provider.thenTmdbKey]). The key asks
 * for no code from the TV: the token in the QR code is enough to reach the paste box, and the worst a
 * stolen link could do is save someone else's key, which the TV checks with TMDB first anyway.
 */
class PairingServer(
    private val scope: CoroutineScope,
    private val fonts: PairingFonts,
    /** The app's strings, for what the phone is told once the TV answers. */
    private val text: (Int) -> String,
    sessionTimeoutMillis: Long = TimeUnit.MINUTES.toMillis(SESSION_TIMEOUT_MINUTES),
) {

    /** Ends a session left listening, so a code on screen isn't left open on the network for ever. */
    private val timer = SessionTimer(scope, sessionTimeoutMillis) {
        if (running && _state.value is State.Listening) {
            stop()
            _state.value = State.Failed(ServerProblem.TIMED_OUT)
        }
    }

    /** What the phone is sent to fill in. */
    sealed interface Mode {
        /**
         * A provider's details; [thenTmdbKey], once its channels are in, Onboarding's TMDB Key on the
         * same address, for the first provider.
         */
        data class Provider(val thenTmdbKey: Boolean = false) : Mode

        /**
         * The TMDB Key, [onboarding] in Onboarding's words: optional, with how to get one folded away.
         * [thenOpenSubtitles] carries the phone straight on to OpenSubtitles' sign-in, on the same
         * address, once a key is saved or skipped, as the TV goes on to that step.
         */
        data class TmdbKey(val onboarding: Boolean = false, val thenOpenSubtitles: Boolean = false) : Mode

        /** Signing the TV in to OpenSubtitles; [onboarding] adds Not now, for Setup's step. */
        data class OpenSubtitles(val onboarding: Boolean = false) : Mode
    }

    data class Session(
        /** What the QR code encodes, e.g. `http://192.168.0.14:41234/?t=…` */
        val url: String,
        /** Shown on the TV, typed on the phone. */
        val confirmCode: String,
        val host: String,
        val port: Int,
        /** The path of [shortUrl]: seven characters that can't be misread for each other. */
        val shortToken: String,
    ) {
        /**
         * The address to type if the QR will not scan, e.g. `192.168.0.14:41234/k7mx4ht`. No
         * `http://`: every phone browser adds it, and it is one more thing to read off a TV.
         */
        val shortUrl: String get() = "$host:$port/$shortToken"
    }

    sealed interface State {
        data object Idle : State
        data class Listening(val session: Session) : State

        /**
         * The phone submitted details. The UI takes it from here, and [answer]s once the login is
         * proven or has failed: the phone waits for that, so it can say "All done" or show why.
         */
        class Received(val draft: Source, private val reply: CompletableDeferred<Reply>) : State {
            /** [error] is why the login failed, or null once the channels are in. */
            fun answer(error: String?) {
                val answered = if (error == null) Reply.AllDone else Reply.LoginFailed(error)
                reply.complete(answered)
            }
        }

        /**
         * The phone sent a TMDB Key. The UI checks it with TMDB and [answer]s: the phone waits for
         * that, so it can say whether the key was saved or why not.
         */
        class KeyReceived(val key: String, private val reply: CompletableDeferred<Reply>) : State {
            /** [problem] is why the key wasn't saved, or null once it has been. */
            fun answer(problem: ViewerError?) {
                reply.complete(if (problem == null) Reply.KeySaved else Reply.KeyTurnedDown(problem))
            }
        }

        /**
         * The phone sent an OpenSubtitles username and password. The UI signs in and [answer]s: the
         * phone waits for that, so it can say whether it worked or why not.
         */
        class SignInReceived(
            val username: String,
            val password: String,
            private val reply: CompletableDeferred<Reply>,
        ) : State {
            /** [problem] is why the sign-in failed, or null once it worked. */
            fun answer(problem: ViewerError?) {
                reply.complete(if (problem == null) Reply.KeySaved else Reply.KeyTurnedDown(problem))
            }
        }

        /** The phone chose Skip for now (or Not now) on a Setup page: the TV carries on without it. */
        data object KeySkipped : State

        /** [code] is the exception's type, for the detail line, when one was behind it. */
        data class Failed(val problem: ServerProblem, val code: String? = null) : State
    }

    /** What the phone waiting on the TV is told. */
    sealed interface Reply {
        data object AllDone : Reply

        data class LoginFailed(val reason: String) : Reply

        /** The TV left pairing before it answered. */
        data object Stopped : Reply

        data object KeySaved : Reply

        data class KeyTurnedDown(val problem: ViewerError) : Reply
    }

    private val _state = MutableStateFlow<State>(State.Idle)
    val state: StateFlow<State> = _state.asStateFlow()

    // Written by start()/stop() on the main thread, read by the accept thread.
    @Volatile private var address: PhoneAddress? = null

    @Volatile private var confirmCode: String = ""

    // Only the accept thread changes these after start(), one request at a time.
    @Volatile private var wrongCodeAttempts = 0

    @Volatile private var running = false

    /** The session listening, to go back to when a login from the phone fails. */
    @Volatile private var session: Session? = null

    /** What the phone is filling in; a provider that carries on becomes Onboarding's TMDB Key. */
    @Volatile private var mode: Mode = Mode.Provider()

    /** What the phone waiting on the TV will be told; null when none is waiting. */
    @Volatile private var pending: CompletableDeferred<Reply>? = null

    /**
     * Starts listening for [mode]. Already listening, it carries on with the same address, now for
     * [mode]: the TMDB step takes over the server the first provider came through.
     */
    fun start(mode: Mode = Mode.Provider()) {
        this.mode = mode
        // Taken over for the key, it gets a whole session: the provider before it may have used most of
        // one loading its channels, which left the TMDB step's code expiring under the viewer.
        if (!running) {
            open()
        } else if (mode is Mode.TmdbKey || mode is Mode.OpenSubtitles) {
            timer.arm()
            // A skip from the TMDB step's page is that step's answer, already heard: the OpenSubtitles
            // step taking the server over starts from listening, or it would take the skip as its own.
            if (mode is Mode.OpenSubtitles && _state.value == State.KeySkipped) {
                session?.let { _state.value = State.Listening(it) }
            }
        }
    }

    private fun open() {
        val opened = when (val opening = PhoneAddress.open()) {
            is PhoneAddress.Opening.Opened -> opening.address
            PhoneAddress.Opening.NoNetwork -> {
                _state.value = State.Failed(ServerProblem.NO_NETWORK)
                return
            }
            is PhoneAddress.Opening.NoSocket -> {
                _state.value = State.Failed(ServerProblem.COULDNT_START, opening.code)
                return
            }
        }

        confirmCode = "%06d".format(SecureRandom().nextInt(1_000_000))
        wrongCodeAttempts = 0
        address = opened
        running = true

        val session = Session(
            url = opened.url,
            confirmCode = confirmCode,
            host = opened.host,
            port = opened.port,
            shortToken = opened.shortToken,
        )
        this.session = session
        _state.value = State.Listening(session)

        // A plain thread rather than a coroutine: ServerSocket.accept() blocks
        // uninterruptibly, and the only reliable way to stop it is to close the socket.
        thread(name = "opentv-pairing", isDaemon = true) {
            while (running) {
                val client = try {
                    opened.accept()
                } catch (_: Exception) {
                    break // socket closed, which is how we stop
                }
                runCatching { handle(client) }
                    .onFailure { Log.w(TAG, "Pairing request failed", it) }
                runCatching { client.close() }
            }
        }

        timer.arm()
    }

    fun stop() {
        // A phone still waiting on the TV is told now, not when its wait runs out.
        pending?.complete(Reply.Stopped)
        timer.cancel()
        running = false
        address?.close()
        address = null
        confirmCode = ""
        // Whatever the phone sent goes with the session: the server is shared, and a key left as
        // KeyReceived was handed to the next screen to show TMDB, which saved it again. Only a failure
        // stays, for the screen that's still saying why.
        if (_state.value !is State.Failed) _state.value = State.Idle
    }

    // ---- Request handling ------------------------------------------------------------------

    private fun handle(client: Socket) {
        client.soTimeout = SOCKET_TIMEOUT_MILLIS

        if (!PairingHttp.isPrivateAddress(client.inetAddress?.hostAddress)) {
            client.getOutputStream().write(
                PairingHttp.httpResponse("403 Forbidden", "text/plain", "Local network only."),
            )
            return
        }

        val reader = BufferedReader(InputStreamReader(client.getInputStream(), Charsets.UTF_8))
        val requestLine = reader.readLine() ?: return
        val (method, path, query) = PairingHttp.parseRequestLine(requestLine) ?: return

        val headers = HashMap<String, String>()
        while (true) {
            val line = reader.readLine() ?: break
            if (line.isEmpty()) break
            PairingHttp.parseHeader(line)?.let { (name, value) -> headers[name] = value }
        }

        val contentLength = (headers["content-length"]?.toIntOrNull() ?: 0)
            .coerceAtMost(MAX_BODY_BYTES)
        val body = if (contentLength > 0) {
            val buffer = CharArray(contentLength)
            val read = reader.read(buffer, 0, contentLength)
            if (read > 0) String(buffer, 0, read) else ""
        } else {
            ""
        }

        val output = client.getOutputStream()

        // Every route checks a token. Without one the page does not exist. The short token
        // only opens the form, which carries the full token for the submit.
        val address = address ?: return
        val supplied = query["t"] ?: PairingHttp.parseFormEncoded(body)["t"]
        val check = address.check(method, path, supplied)
        if (check is PhoneAddress.Check.Refused) {
            output.write(check.response())
            return
        }
        val token = address.token
        // As the request found it: the TV's TMDB step may change it while a provider is still loading.
        val asked = mode

        when {
            method == "GET" && path.startsWith(FONTS_PATH) -> output.write(font(path.removePrefix(FONTS_PATH)))

            method == "GET" -> output.write(html(phonePage(token, null, asked, text)))

            method == "POST" && path.startsWith("/submit") && asked is Mode.Provider ->
                handleSubmit(output, body, asked)

            method == "POST" && answersAt(asked, path) -> handleAnswered(output, body, asked)

            // Only Setup's pages have Skip: in Settings there's nothing to carry on to.
            method == "POST" && path.startsWith("/skip") && asked.isSetupStep() -> handleSkip(output, asked)

            else -> output.write(
                PairingHttp.httpResponse("405 Method Not Allowed", "text/plain", "No."),
            )
        }
    }

    private fun font(name: String): ByteArray {
        val bytes = fonts.open(name) ?: return PairingHttp.httpResponse("404 Not Found", "text/plain", "Not found.")
        return PairingHttp.httpResponse("200 OK", "font/ttf", bytes)
    }

    private fun handleSubmit(output: java.io.OutputStream, body: String, asked: Mode.Provider) {
        val token = address?.token.orEmpty()
        val form = PairingHttp.parseFormEncoded(body)
        if (!codeMatches(output, form, token)) return
        val draft = draftFrom(form)
        if (draft == null) {
            output.write(html(PairingPage.form(token, "Please fill in the server address.")))
            return
        }
        val reply = CompletableDeferred<Reply>()
        pending = reply
        _state.value = State.Received(draft, reply)
        // The phone waits on the channels loading, so it can say whether the login worked: a big
        // provider's took about 13 seconds on a Fire TV Stick. The TV answering nothing at all falls
        // back to what the phone was told before it waited.
        val answer = runBlocking { withTimeoutOrNull(REPLY_TIMEOUT_MILLIS) { reply.await() } }
        pending = null
        when {
            answer is Reply.LoginFailed -> {
                // Still listening, with the same code and address, so the phone can put it right. Before
                // the page goes, or the phone could have it, and try again, while this still says received.
                session?.let { _state.value = State.Listening(it) }
                output.write(html(PairingPage.form(token, answer.reason)))
            }
            answer == Reply.AllDone && asked.thenTmdbKey -> {
                // Straight on to the TMDB Key, on the same address, as the TV moves to its TMDB step.
                val next = Mode.TmdbKey(onboarding = true)
                mode = next
                timer.arm()
                session?.let { _state.value = State.Listening(it) }
                output.write(html(keyPage(token, null, next, text)))
            }
            else -> finish(output, afterProvider(answer, text))
        }
    }

    /**
     * Whether the phone sent the code on the TV. If not, it's told so, and after too many wrong
     * codes the session is burned.
     */
    private fun codeMatches(output: java.io.OutputStream, form: Map<String, String>, token: String): Boolean {
        val code = form["code"].orEmpty().filter { it.isDigit() }
        if (PairingHttp.secretsMatch(code, confirmCode)) return true
        wrongCodeAttempts++
        if (wrongCodeAttempts >= MAX_CODE_ATTEMPTS) {
            output.write(
                PairingHttp.httpResponse(
                    "403 Forbidden",
                    "text/html",
                    PairingPage.message("Too many attempts", "Start pairing again on the TV to get a new code."),
                ),
            )
            stop()
            _state.value = State.Failed(ServerProblem.TOO_MANY_CODES)
        } else {
            val left = MAX_CODE_ATTEMPTS - wrongCodeAttempts
            output.write(
                html(PairingPage.form(token, "That code doesn't match the one on the TV. $left attempts left.")),
            )
        }
        return false
    }

    /**
     * The phone is done with its page. [then] carries it straight on to that step's page, at the same
     * address, as the TV goes on to the step: a new address would mean scanning a new code. Otherwise
     * it gets [page] and the server shuts now, not leaving a socket open on the network for the rest
     * of the timeout.
     */
    private fun finish(output: java.io.OutputStream, page: String, then: Mode? = null) {
        if (then != null) {
            mode = then
            timer.arm()
            output.write(html(phonePage(address?.token.orEmpty(), null, then, text)))
            return
        }
        output.write(html(page))
        output.flush()
        running = false
        address?.close()
        address = null
    }

    /**
     * Something the phone sent for the TV to check, a TMDB Key or an OpenSubtitles sign-in: handed
     * on as a state carrying the reply the phone waits for, then the phone hears it was saved, or gets
     * its page back saying why not. A sign-in's password is held only until the TV has it.
     */
    private fun handleAnswered(output: java.io.OutputStream, body: String, asked: Mode) {
        val token = address?.token.orEmpty()
        val reply = CompletableDeferred<Reply>()
        val received = receivedFrom(PairingHttp.parseFormEncoded(body), asked, reply)
        if (received == null) {
            output.write(html(phonePage(token, null, asked, text)))
            return
        }
        pending = reply
        _state.value = received
        // TMDB and OpenSubtitles answer in a second or two; the wait is only for a TV that never answers.
        val answer = runBlocking { withTimeoutOrNull(REPLY_TIMEOUT_MILLIS) { reply.await() } }
        pending = null
        if (answer is Reply.KeyTurnedDown) {
            // Still listening, with the same address, so the phone can try again: before the page goes,
            // as for a provider's failed login.
            session?.let { _state.value = State.Listening(it) }
            output.write(html(phonePage(token, answer.problem, asked, text)))
            return
        }
        // A TMDB Key saved during Setup goes on to OpenSubtitles' sign-in, as the TV does.
        val then = Mode.OpenSubtitles(onboarding = true).takeIf { answer == Reply.KeySaved && asked.handsOn() }
        if (then != null) session?.let { _state.value = State.Listening(it) }
        finish(output, afterAnswer(answer, asked, text), then)
    }

    private fun handleSkip(output: java.io.OutputStream, asked: Mode) {
        _state.value = State.KeySkipped
        val body = if (asked is Mode.OpenSubtitles) {
            R.string.pairing_phone_opensubtitles_skipped_body
        } else {
            R.string.pairing_phone_tmdb_skipped_body
        }
        // The TV hears the skip (KeySkipped) and moves on; the phone moves on with it, if there's a step.
        val then = Mode.OpenSubtitles(onboarding = true).takeIf { asked.handsOn() }
        finish(output, PairingPage.message(text(R.string.pairing_phone_tmdb_skipped_title), text(body)), then)
    }

    private companion object {
        const val TAG = "PairingServer"
        const val SESSION_TIMEOUT_MINUTES = 5L
        const val SOCKET_TIMEOUT_MILLIS = 15_000
        const val REPLY_TIMEOUT_MILLIS = 5L * 60 * 1000
        const val MAX_BODY_BYTES = 16 * 1024
        const val MAX_CODE_ATTEMPTS = 5

        // Fonts take the full token too, as every route does, so they only load for the form.
        const val FONTS_PATH = "/fonts/"
    }
}

/** [page] as the phone gets it. */
private fun html(page: String): ByteArray = PairingHttp.httpResponse("200 OK", "text/html", page)

/** The provider the phone's form describes; null without a server address. */
private fun draftFrom(form: Map<String, String>): Source? {
    val kind = if (form["kind"] == "m3u") SourceKind.M3U else SourceKind.XTREAM
    val url = form["url"].orEmpty().trim().ifEmpty { return null }
    return Source(
        name = form["name"].orEmpty().trim().ifBlank {
            if (kind == SourceKind.XTREAM) "My provider" else "My playlist"
        },
        kind = kind,
        url = url,
        username = form["username"]?.trim()?.takeIf { it.isNotEmpty() },
        password = form["password"]?.takeIf { it.isNotEmpty() },
        epgUrl = form["epg"]?.trim()?.takeIf { it.isNotEmpty() },
    )
}

/** What the phone is told once the TV has answered about a provider: all done, stopped, or nothing heard. */
private fun afterProvider(answer: PairingServer.Reply?, text: (Int) -> String): String = when (answer) {
    PairingServer.Reply.AllDone -> PairingPage.message(
        text(R.string.pairing_phone_all_done_title),
        text(R.string.pairing_phone_all_done_body),
    )
    PairingServer.Reply.Stopped -> PairingPage.message(
        text(R.string.pairing_phone_stopped_title),
        text(R.string.pairing_phone_stopped_body),
    )
    else -> PairingPage.message(
        "Sent to your TV",
        "You can put your phone down. The rest happens on the telly.",
    )
}

/** The TMDB Key's page, in Onboarding's words when [asked] says so. */
private fun keyPage(token: String, problem: ViewerError?, asked: PairingServer.Mode.TmdbKey, text: (Int) -> String) =
    PairingPage.tmdbKey(
        token = token,
        text = text,
        error = problem?.let { PairingPage.Error(it.sentence, it.detail) },
        onboarding = asked.onboarding,
    )

/** The page the QR code opens for [asked], with why the TV turned the last try down when it did. */
private fun phonePage(token: String, problem: ViewerError?, asked: PairingServer.Mode, text: (Int) -> String) =
    when (asked) {
        is PairingServer.Mode.Provider -> PairingPage.form(token, null)
        is PairingServer.Mode.TmdbKey -> keyPage(token, problem, asked, text)
        is PairingServer.Mode.OpenSubtitles -> PairingPage.openSubtitles(
            token,
            text,
            problem?.let { PairingPage.Error(it.sentence, it.detail) },
            asked.onboarding,
        )
    }

/** Whether a POST to [path] is what [asked]'s page sends for the TV to check. */
private fun answersAt(asked: PairingServer.Mode, path: String): Boolean = when (asked) {
    is PairingServer.Mode.TmdbKey -> path.startsWith("/key")
    is PairingServer.Mode.OpenSubtitles -> path.startsWith("/signin")
    is PairingServer.Mode.Provider -> false
}

/** Whether [this] goes on to OpenSubtitles' sign-in once done: Setup's TMDB step, with that step next. */
private fun PairingServer.Mode.handsOn(): Boolean = (this as? PairingServer.Mode.TmdbKey)?.thenOpenSubtitles == true

/** Whether [this] is one of Setup's optional steps, whose page has Skip. */
private fun PairingServer.Mode.isSetupStep(): Boolean = when (this) {
    is PairingServer.Mode.TmdbKey -> onboarding
    is PairingServer.Mode.OpenSubtitles -> onboarding
    is PairingServer.Mode.Provider -> false
}

/** What the TV is handed from [form], or null when the form left out what it needs. */
private fun receivedFrom(
    form: Map<String, String>,
    asked: PairingServer.Mode,
    reply: CompletableDeferred<PairingServer.Reply>,
): PairingServer.State? = when (asked) {
    is PairingServer.Mode.TmdbKey ->
        form["key"].orEmpty().trim().takeIf { it.isNotEmpty() }?.let { PairingServer.State.KeyReceived(it, reply) }
    is PairingServer.Mode.OpenSubtitles -> {
        val username = form["username"].orEmpty().trim()
        val password = form["password"].orEmpty()
        if (username.isEmpty() ||
            password.isEmpty()
        ) {
            null
        } else {
            PairingServer.State.SignInReceived(username, password, reply)
        }
    }
    is PairingServer.Mode.Provider -> null
}

/** What the phone is told once the TV has answered: saved, or the TV left before it did. */
private fun afterAnswer(answer: PairingServer.Reply?, asked: PairingServer.Mode, text: (Int) -> String): String = when {
    answer == PairingServer.Reply.KeySaved && asked is PairingServer.Mode.OpenSubtitles -> PairingPage.message(
        text(R.string.pairing_phone_opensubtitles_saved_title),
        text(R.string.pairing_phone_opensubtitles_saved_body),
    )
    answer == PairingServer.Reply.KeySaved -> PairingPage.message(
        text(R.string.pairing_phone_tmdb_saved_title),
        text(R.string.pairing_phone_tmdb_saved_body),
    )
    else -> PairingPage.message(
        text(R.string.pairing_phone_stopped_title),
        text(R.string.pairing_phone_stopped_body),
    )
}

/** Runs [onExpiry] [millis] after the last [arm], unless [cancel]led first. */
private class SessionTimer(
    private val scope: CoroutineScope,
    private val millis: Long,
    private val onExpiry: () -> Unit,
) {
    @Volatile private var running: Job? = null

    fun arm() {
        running?.cancel()
        running = scope.launch(Dispatchers.Default) {
            delay(millis)
            onExpiry()
        }
    }

    fun cancel() {
        running?.cancel()
        running = null
    }
}
