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
 * The same server takes the viewer's TMDB Key ([Mode.TMDB_KEY]), from Settings › TMDB, Onboarding's
 * TMDB step, or straight on from the first provider ([Mode.PROVIDER_THEN_TMDB_KEY]). The key asks
 * for no code from the TV: the token in the QR code is enough to reach the paste box, and the worst a
 * stolen link could do is save someone else's key, which the TV checks with TMDB first anyway.
 */
class PairingServer(
    private val scope: CoroutineScope,
    private val fonts: PairingFonts,
    /** The app's strings, for what the phone is told once the TV answers. */
    private val text: (Int) -> String,
) {

    /** What the phone is sent to fill in. */
    enum class Mode {
        /** A provider's details. */
        PROVIDER,

        /** A provider's details, then, once its channels are in, the TMDB Key: Onboarding's first provider. */
        PROVIDER_THEN_TMDB_KEY,

        /** The TMDB Key alone. */
        TMDB_KEY,
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

    /** What the phone is filling in; [Mode.PROVIDER_THEN_TMDB_KEY] becomes [Mode.TMDB_KEY] once the provider's in. */
    @Volatile private var mode: Mode = Mode.PROVIDER

    /** Whether this session started with a provider, so its TMDB Key page is Onboarding's. */
    private val cameThroughProvider get() = startedAs == Mode.PROVIDER_THEN_TMDB_KEY

    @Volatile private var startedAs: Mode = Mode.PROVIDER

    /** What the phone waiting on the TV will be told; null when none is waiting. */
    @Volatile private var pending: CompletableDeferred<Reply>? = null

    /**
     * Starts listening for [mode]. Already listening, it carries on with the same address, now for
     * [mode]: the TMDB step takes over the server the first provider came through.
     */
    fun start(mode: Mode = Mode.PROVIDER) {
        this.mode = mode
        if (running) return
        startedAs = mode
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

        scope.launch(Dispatchers.Default) {
            kotlinx.coroutines.delay(TimeUnit.MINUTES.toMillis(SESSION_TIMEOUT_MINUTES))
            if (running && _state.value is State.Listening) {
                stop()
                _state.value = State.Failed(ServerProblem.TIMED_OUT)
            }
        }
    }

    fun stop() {
        // A phone still waiting on the TV is told now, not when its wait runs out.
        pending?.complete(Reply.Stopped)
        running = false
        address?.close()
        address = null
        confirmCode = ""
        if (_state.value is State.Listening) _state.value = State.Idle
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

        when {
            method == "GET" && path.startsWith(FONTS_PATH) -> output.write(font(path.removePrefix(FONTS_PATH)))

            method == "GET" && mode == Mode.TMDB_KEY -> output.write(
                PairingHttp.httpResponse("200 OK", "text/html", keyPage(token, null)),
            )

            method == "GET" -> output.write(
                PairingHttp.httpResponse("200 OK", "text/html", PairingPage.form(token, null)),
            )

            method == "POST" && path.startsWith("/submit") && mode != Mode.TMDB_KEY -> handleSubmit(output, body)

            method == "POST" && path.startsWith("/key") && mode == Mode.TMDB_KEY -> handleKey(output, body)

            else -> output.write(
                PairingHttp.httpResponse("405 Method Not Allowed", "text/plain", "No."),
            )
        }
    }

    private fun font(name: String): ByteArray {
        val bytes = fonts.open(name) ?: return PairingHttp.httpResponse("404 Not Found", "text/plain", "Not found.")
        return PairingHttp.httpResponse("200 OK", "font/ttf", bytes)
    }

    private fun handleSubmit(output: java.io.OutputStream, body: String) {
        val token = address?.token.orEmpty()
        val form = PairingHttp.parseFormEncoded(body)

        val code = form["code"].orEmpty().filter { it.isDigit() }
        if (!PairingHttp.secretsMatch(code, confirmCode)) {
            wrongCodeAttempts++
            if (wrongCodeAttempts >= MAX_CODE_ATTEMPTS) {
                output.write(
                    PairingHttp.httpResponse(
                        "403 Forbidden",
                        "text/html",
                        PairingPage.message(
                            "Too many attempts",
                            "Start pairing again on the TV to get a new code.",
                        ),
                    ),
                )
                stop()
                _state.value = State.Failed(ServerProblem.TOO_MANY_CODES)
                return
            }
            output.write(
                PairingHttp.httpResponse(
                    "200 OK",
                    "text/html",
                    PairingPage.form(
                        token,
                        "That code doesn't match the one on the TV. " +
                            "${MAX_CODE_ATTEMPTS - wrongCodeAttempts} attempts left.",
                    ),
                ),
            )
            return
        }

        val kind = if (form["kind"] == "m3u") SourceKind.M3U else SourceKind.XTREAM
        val url = form["url"].orEmpty().trim()
        if (url.isEmpty()) {
            output.write(
                PairingHttp.httpResponse(
                    "200 OK",
                    "text/html",
                    PairingPage.form(token, "Please fill in the server address."),
                ),
            )
            return
        }

        val draft = Source(
            name = form["name"].orEmpty().trim().ifBlank {
                if (kind == SourceKind.XTREAM) "My provider" else "My playlist"
            },
            kind = kind,
            url = url,
            username = form["username"]?.trim()?.takeIf { it.isNotEmpty() },
            password = form["password"]?.takeIf { it.isNotEmpty() },
            epgUrl = form["epg"]?.trim()?.takeIf { it.isNotEmpty() },
        )

        val reply = CompletableDeferred<Reply>()
        pending = reply
        _state.value = State.Received(draft, reply)
        // The phone waits on the channels loading, so it can say whether the login worked: a big
        // provider's took about 13 seconds on a Fire TV Stick. The TV answering nothing at all falls
        // back to what the phone was told before it waited.
        val answer = runBlocking { withTimeoutOrNull(REPLY_TIMEOUT_MILLIS) { reply.await() } }
        pending = null
        if (answer is Reply.LoginFailed) {
            output.write(PairingHttp.httpResponse("200 OK", "text/html", PairingPage.form(token, answer.reason)))
            // Still listening, with the same code and address, so the phone can put it right.
            session?.let { _state.value = State.Listening(it) }
            return
        }
        if (answer == Reply.AllDone && mode == Mode.PROVIDER_THEN_TMDB_KEY) {
            // Straight on to the TMDB Key, on the same address, as the TV moves to its TMDB step.
            mode = Mode.TMDB_KEY
            output.write(PairingHttp.httpResponse("200 OK", "text/html", keyPage(token, null)))
            session?.let { _state.value = State.Listening(it) }
            return
        }
        val page = when (answer) {
            Reply.AllDone -> PairingPage.message(
                text(R.string.pairing_phone_all_done_title),
                text(R.string.pairing_phone_all_done_body),
            )
            Reply.Stopped -> PairingPage.message(
                text(R.string.pairing_phone_stopped_title),
                text(R.string.pairing_phone_stopped_body),
            )
            else -> PairingPage.message(
                "Sent to your TV",
                "You can put your phone down. The rest happens on the telly.",
            )
        }
        output.write(PairingHttp.httpResponse("200 OK", "text/html", page))
        output.flush()
        closeDoor()
    }

    /** Nothing more to receive: shut it now, not leave a socket open on the network for the rest of the timeout. */
    private fun closeDoor() {
        running = false
        address?.close()
        address = null
    }

    private fun handleKey(output: java.io.OutputStream, body: String) {
        val token = address?.token.orEmpty()
        val key = PairingHttp.parseFormEncoded(body)["key"].orEmpty().trim()
        if (key.isEmpty()) {
            output.write(PairingHttp.httpResponse("200 OK", "text/html", keyPage(token, null)))
            return
        }
        val reply = CompletableDeferred<Reply>()
        pending = reply
        _state.value = State.KeyReceived(key, reply)
        // TMDB answers a check in a second or two; the wait is only for a TV that never answers.
        val answer = runBlocking { withTimeoutOrNull(REPLY_TIMEOUT_MILLIS) { reply.await() } }
        pending = null
        val page = when (answer) {
            Reply.KeySaved -> PairingPage.message(
                text(R.string.pairing_phone_tmdb_saved_title),
                text(R.string.pairing_phone_tmdb_saved_body),
            )
            is Reply.KeyTurnedDown -> {
                output.write(PairingHttp.httpResponse("200 OK", "text/html", keyPage(token, answer.problem)))
                // Still listening, with the same address, so the phone can paste another.
                session?.let { _state.value = State.Listening(it) }
                return
            }
            else -> PairingPage.message(
                text(R.string.pairing_phone_stopped_title),
                text(R.string.pairing_phone_stopped_body),
            )
        }
        output.write(PairingHttp.httpResponse("200 OK", "text/html", page))
        output.flush()
        closeDoor()
    }

    /** The TMDB Key's page, the Onboarding version once a provider has come through first. */
    private fun keyPage(token: String, problem: ViewerError?): String = PairingPage.tmdbKey(
        token = token,
        words = PairingPage.TmdbKeyWords(
            title = text(R.string.pairing_phone_tmdb_title),
            sub = text(R.string.pairing_phone_tmdb_sub),
            optional = text(R.string.pairing_phone_tmdb_optional),
            label = text(R.string.pairing_phone_tmdb_label),
            send = text(R.string.pairing_phone_tmdb_send),
            how = text(R.string.pairing_phone_tmdb_how),
            step1 = text(R.string.pairing_phone_tmdb_step1),
            step2 = text(R.string.pairing_phone_tmdb_step2),
            step3 = text(R.string.pairing_phone_tmdb_step3),
            step4 = text(R.string.pairing_phone_tmdb_step4),
        ),
        error = problem?.let { PairingPage.Error(it.sentence, it.detail) },
        onboarding = cameThroughProvider,
    )

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
