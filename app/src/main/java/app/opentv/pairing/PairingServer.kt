/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv.pairing

import android.util.Log
import app.opentv.data.model.Source
import app.opentv.data.model.SourceKind
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.InetAddress
import java.net.NetworkInterface
import java.net.ServerSocket
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
 * - It binds to the LAN on a **random high port**, and only while the pairing screen is open.
 * - The QR code's URL carries a **192-bit random token**. Knowing the IP and port is not enough.
 * - For a phone whose camera won't scan, the screen also shows a typeable address with a
 *   **seven-character short token** instead (21 symbols, about 30 bits). It is weaker than the
 *   QR's, so after [MAX_SHORT_TOKEN_GUESSES] wrong ones the typed address is switched off for the
 *   session (the QR keeps working): guessing it is then hopeless, not merely unlikely. It only
 *   reaches the form; the code below is still needed to send anything.
 * - A wrong token gets a bare 404, so a scan of the network learns nothing. The exception is a
 *   path shaped like a short token, which gets a page telling the viewer to check what they typed;
 *   by then whoever sent it already knows the server is there.
 * - The phone must also enter a **six-digit code shown on the TV**. That is the part the user
 *   sees, and it is what defends against someone who somehow obtained the link.
 * - Requests from non-private addresses are refused outright.
 * - After [SESSION_TIMEOUT_MINUTES], or on success, the socket closes and the token is void.
 * - Five wrong codes and the session is burned.
 */
class PairingServer(private val scope: CoroutineScope, private val fonts: PairingFonts) {

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
        class Received(val draft: Source, private val reply: CompletableDeferred<String?>) : State {
            /** [error] is why the login failed, or null once the channels are in. */
            fun answer(error: String?) {
                reply.complete(error)
            }
        }
        data class Failed(val reason: String) : State
    }

    private val _state = MutableStateFlow<State>(State.Idle)
    val state: StateFlow<State> = _state.asStateFlow()

    private var serverSocket: ServerSocket? = null

    // Written by start()/stop() on the main thread, read by the accept thread.
    @Volatile private var token: String = ""

    /** Empty once [MAX_SHORT_TOKEN_GUESSES] wrong ones have switched the typed address off. */
    @Volatile private var shortToken: String = ""

    @Volatile private var confirmCode: String = ""

    // Only the accept thread changes these after start(), one request at a time.
    @Volatile private var wrongCodeAttempts = 0

    @Volatile private var wrongShortTokenGuesses = 0

    @Volatile private var running = false

    /** The session listening, to go back to when a login from the phone fails. */
    @Volatile private var session: Session? = null

    fun start() {
        if (running) return
        val host = lanAddress()
        if (host == null) {
            _state.value = State.Failed(
                "This device doesn't appear to be on a network, so it can't talk to your phone.",
            )
            return
        }

        val random = SecureRandom()
        token = ByteArray(24).also(random::nextBytes).joinToString("") { "%02x".format(it) }
        // Not just a shorter hex token: nothing that looks like another character across a
        // room in the TV's serif face (0/o, 1/l/i, 2/z, 5/s, 6/b, 9/g/q, u/v/y).
        shortToken = (1..SHORT_TOKEN_LENGTH)
            .map { SHORT_TOKEN_ALPHABET[random.nextInt(SHORT_TOKEN_ALPHABET.length)] }
            .joinToString("")
        confirmCode = "%06d".format(random.nextInt(1_000_000))
        wrongCodeAttempts = 0
        wrongShortTokenGuesses = 0

        val socket = try {
            // Port 0 asks the OS for a free one. A fixed port would be predictable and would
            // clash with whatever else the user runs at home.
            ServerSocket(0)
        } catch (e: Exception) {
            Log.w(TAG, "Could not open a socket", e)
            _state.value = State.Failed("Could not start the pairing server on this device.")
            return
        }

        serverSocket = socket
        running = true

        val session = Session(
            url = "http://$host:${socket.localPort}/?t=$token",
            confirmCode = confirmCode,
            host = host,
            port = socket.localPort,
            shortToken = shortToken,
        )
        this.session = session
        _state.value = State.Listening(session)

        // A plain thread rather than a coroutine: ServerSocket.accept() blocks
        // uninterruptibly, and the only reliable way to stop it is to close the socket.
        thread(name = "opentv-pairing", isDaemon = true) {
            while (running) {
                val client = try {
                    socket.accept()
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
                _state.value = State.Failed("Pairing timed out. Start again when you're ready.")
            }
        }
    }

    fun stop() {
        running = false
        runCatching { serverSocket?.close() }
        serverSocket = null
        token = ""
        shortToken = ""
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
        val supplied = query["t"] ?: PairingHttp.parseFormEncoded(body)["t"] ?: ""
        val hasFullToken = token.isNotEmpty() && PairingHttp.secretsMatch(supplied, token)
        val typed = if (method == "GET") path.trim('/').lowercase() else ""
        val hasShortToken = shortToken.isNotEmpty() && PairingHttp.secretsMatch(typed, shortToken)
        if (!hasFullToken && !hasShortToken) {
            output.write(refusal(typed))
            return
        }

        when {
            method == "GET" && path.startsWith(FONTS_PATH) -> output.write(font(path.removePrefix(FONTS_PATH)))

            method == "GET" -> output.write(
                PairingHttp.httpResponse("200 OK", "text/html", PairingPage.form(token, null)),
            )

            method == "POST" && path.startsWith("/submit") -> handleSubmit(output, body)

            else -> output.write(
                PairingHttp.httpResponse("405 Method Not Allowed", "text/plain", "No."),
            )
        }
    }

    private fun font(name: String): ByteArray {
        val bytes = fonts.open(name) ?: return PairingHttp.httpResponse("404 Not Found", "text/plain", "Not found.")
        return PairingHttp.httpResponse("200 OK", "font/ttf", bytes)
    }

    private fun refusal(typed: String): ByteArray {
        // Same length and letters-and-digits, so a look-alike mistype still counts as a try.
        val looksTyped = token.isNotEmpty() &&
            typed.length == SHORT_TOKEN_LENGTH &&
            typed.all { it.isLetterOrDigit() }
        if (!looksTyped) {
            return PairingHttp.httpResponse("404 Not Found", "text/plain", "Not found.")
        }
        if (shortToken.isNotEmpty() && ++wrongShortTokenGuesses >= MAX_SHORT_TOKEN_GUESSES) {
            shortToken = ""
        }
        val page = if (shortToken.isEmpty()) {
            PairingPage.message(
                "That address has stopped working",
                "Too many wrong addresses were tried, so it's switched off. " +
                    "Use the camera to scan the QR code, or start again on the TV.",
            )
        } else {
            PairingPage.message(
                "That address didn't work",
                "Check the address against the one on the TV and try again.",
            )
        }
        return PairingHttp.httpResponse("404 Not Found", "text/html", page)
    }

    private fun handleSubmit(output: java.io.OutputStream, body: String) {
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
                _state.value = State.Failed("Too many incorrect codes. Pairing cancelled.")
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

        val reply = CompletableDeferred<String?>()
        _state.value = State.Received(draft, reply)
        // The phone waits on the channels loading, so it can say whether the login worked: a big
        // provider's took about 13 seconds on a Fire TV Stick. The TV answering nothing (it left the
        // screen) falls back to what the phone was told before it waited.
        val answer = runBlocking { withTimeoutOrNull(REPLY_TIMEOUT_MILLIS) { Answered(reply.await()) } }
        val error = answer?.error
        if (error != null) {
            output.write(PairingHttp.httpResponse("200 OK", "text/html", PairingPage.form(token, error)))
            // Still listening, with the same code and address, so the phone can put it right.
            session?.let { _state.value = State.Listening(it) }
            return
        }
        output.write(
            PairingHttp.httpResponse(
                "200 OK",
                "text/html",
                if (answer != null) {
                    PairingPage.message("All done", "Carry on on your TV.")
                } else {
                    PairingPage.message(
                        "Sent to your TV",
                        "You can put your phone down. The rest happens on the telly.",
                    )
                },
            ),
        )
        output.flush()
        // Nothing more to receive. Shut the door immediately rather than leaving a socket
        // open on the user's network for the rest of the timeout.
        running = false
        runCatching { serverSocket?.close() }
        serverSocket = null
        token = ""
        shortToken = ""
    }

    /** What the TV answered: a login failure's reason, or null for all done. */
    private class Answered(val error: String?)

    // ---- Finding our own address -----------------------------------------------------------

    /**
     * The device's address on the LAN.
     *
     * Enumerating interfaces rather than asking ConnectivityManager, because TV boxes are
     * frequently on ethernet, sometimes on both, and the wifi-shaped API returns nothing
     * useful when the box is wired.
     */
    private fun lanAddress(): String? {
        val candidates = mutableListOf<InetAddress>()
        runCatching {
            for (nic in NetworkInterface.getNetworkInterfaces()) {
                if (!nic.isUp || nic.isLoopback) continue
                for (address in nic.inetAddresses) {
                    if (address.isLoopbackAddress) continue
                    if (address.hostAddress?.contains(':') == true) continue // IPv4 only
                    if (PairingHttp.isPrivateAddress(address.hostAddress)) candidates += address
                }
            }
        }.onFailure { Log.w(TAG, "Could not enumerate interfaces", it) }

        // Prefer a real private range over a 169.254 self-assigned address, which means
        // DHCP failed and nothing will be able to reach us anyway.
        return candidates
            .sortedBy { it.hostAddress?.startsWith("169.254") == true }
            .firstOrNull()
            ?.hostAddress
    }

    companion object {
        /** Wrong typed addresses before the typed address is switched off for the session. */
        const val MAX_SHORT_TOKEN_GUESSES = 10

        private const val TAG = "PairingServer"
        private const val SESSION_TIMEOUT_MINUTES = 5L
        private const val SOCKET_TIMEOUT_MILLIS = 15_000
        private const val REPLY_TIMEOUT_MILLIS = 5L * 60 * 1000
        private const val MAX_BODY_BYTES = 16 * 1024
        private const val MAX_CODE_ATTEMPTS = 5
        private const val SHORT_TOKEN_LENGTH = 7

        // Fonts take the full token too, as every route does, so they only load for the form.
        private const val FONTS_PATH = "/fonts/"
        private const val SHORT_TOKEN_ALPHABET = "346789acdefhjkmnprtwx"
    }
}
