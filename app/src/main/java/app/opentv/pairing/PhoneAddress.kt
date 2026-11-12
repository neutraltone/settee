/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv.pairing

import android.util.Log
import app.opentv.core.ErrorWording
import java.io.IOException
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.security.SecureRandom

/**
 * Where a phone or laptop reaches this TV, and the check every request passes before a server
 * looks at it. Setup ([PairingServer]) and Manage on phone or laptop ([ManagerServer]) both use
 * one, so the two addresses look and behave the same; each server keeps its own lifetime and
 * pages on top.
 */
class PhoneAddress private constructor(
    val host: String,
    private val socket: ServerSocket,
    /** The QR code's long token. Pages a server sends embed it, so the typed address leads somewhere. */
    val token: String,
) {
    val port: Int get() = socket.localPort

    /** The typed address's path. Empty once too many wrong ones have switched it off. */
    @Volatile var shortToken: String = ""
        private set

    // Only a server's accept thread checks requests, one at a time.
    @Volatile private var wrongShortTokenGuesses = 0

    /**
     * The address to type if the QR will not scan, e.g. `192.168.0.14:41234/k7mx4ht`. No
     * `http://`: every phone browser adds it, and it is one more thing to read off a TV.
     */
    val shortUrl: String get() = "$host:$port/$shortToken"

    @Volatile private var closed = false

    /** What the QR code encodes, e.g. `http://192.168.0.14:41234/?t=…` */
    val url: String get() = "http://$host:$port/?t=$token"

    sealed interface Opening {
        data class Opened(val address: PhoneAddress) : Opening
        data object NoNetwork : Opening

        /** [code] is the exception's type, for the detail line under the failure. */
        data class NoSocket(val code: String) : Opening
    }

    sealed interface Check {
        /** The QR code's long token: every route. */
        data object Scanned : Check

        /** The typed address: only a GET of the page, which then carries the long token. */
        data object Typed : Check
        data class Refused(val status: String, val contentType: String, val body: String) : Check {
            fun response(): ByteArray = PairingHttp.httpResponse(status, contentType, body)
        }
    }

    fun check(method: String, path: String, suppliedToken: String?): Check {
        val typed = if (method == "GET") path.trim('/').lowercase() else ""
        return when {
            closed -> NOT_FOUND
            suppliedToken != null && PairingHttp.secretsMatch(suppliedToken, token) -> Check.Scanned
            shortToken.isNotEmpty() && PairingHttp.secretsMatch(typed, shortToken) -> Check.Typed
            // Same length and letters-and-digits, so a look-alike mistype still counts.
            typed.length == SHORT_TOKEN_LENGTH && typed.all { it.isLetterOrDigit() } -> wrongGuess()
            else -> NOT_FOUND
        }
    }

    private fun wrongGuess(): Check.Refused {
        if (shortToken.isNotEmpty() && ++wrongShortTokenGuesses >= MAX_SHORT_TOKEN_GUESSES) {
            shortToken = ""
        }
        val page = if (shortToken.isEmpty()) {
            PairingPage.message(
                "That address has stopped working",
                "Too many wrong addresses were tried, so it's switched off. " +
                    "Use the camera to scan the QR code on the TV instead.",
            )
        } else {
            PairingPage.message(
                "That address didn't work",
                "Check the address against the one on the TV and try again.",
            )
        }
        return Check.Refused("404 Not Found", "text/html", page)
    }

    fun accept(): Socket = socket.accept()

    fun close() {
        closed = true
        runCatching { socket.close() }
        shortToken = ""
    }

    companion object {
        /** Wrong typed addresses before the typed address is switched off. */
        const val MAX_SHORT_TOKEN_GUESSES = 10

        private const val TAG = "PhoneAddress"
        private val NOT_FOUND = Check.Refused("404 Not Found", "text/plain", "Not found.")
        private const val SHORT_TOKEN_LENGTH = 7
        private const val TOKEN_BYTES = 24 // 192 bits
        private const val SHORT_TOKEN_ALPHABET = "346789acdefhjkmnprtwx"

        fun open(): Opening {
            val host = lanAddress() ?: return Opening.NoNetwork
            return try {
                // Port 0 asks the OS for a free one. A fixed port would be predictable and would
                // clash with whatever else the user runs at home.
                val socket = ServerSocket(0)
                Opening.Opened(PhoneAddress(host, socket, newToken()).apply { shortToken = newShortToken() })
            } catch (e: IOException) {
                Log.w(TAG, "Could not open a socket", e)
                Opening.NoSocket(ErrorWording.codeOf(e))
            }
        }

        private val random = SecureRandom()

        private fun newToken() = ByteArray(TOKEN_BYTES).also(random::nextBytes).joinToString("") { "%02x".format(it) }

        // Not just a shorter hex token: nothing that looks like another character across a room
        // in the TV's serif face (0/o, 1/l/i, 2/z, 5/s, 6/b, 9/g/q, u/v/y).
        private fun newShortToken() = (1..SHORT_TOKEN_LENGTH)
            .map { SHORT_TOKEN_ALPHABET[random.nextInt(SHORT_TOKEN_ALPHABET.length)] }
            .joinToString("")

        /**
         * The device's address on the LAN.
         *
         * Enumerating interfaces rather than asking ConnectivityManager, because TV boxes are
         * frequently on ethernet, sometimes on both, and the wifi-shaped API returns nothing
         * useful when the box is wired.
         */
        private fun lanAddress(): String? {
            val candidates = runCatching {
                NetworkInterface.getNetworkInterfaces().asSequence()
                    .filter { it.isUp && !it.isLoopback }
                    .flatMap { it.inetAddresses.asSequence() }
                    .filter { !it.isLoopbackAddress && it.hostAddress?.contains(':') != true } // IPv4 only
                    .filter { PairingHttp.isPrivateAddress(it.hostAddress) }
                    .toList()
            }.onFailure { Log.w(TAG, "Could not enumerate interfaces", it) }.getOrDefault(emptyList())

            // Prefer a real private range over a 169.254 self-assigned address, which means
            // DHCP failed and nothing will be able to reach us anyway.
            return candidates
                .sortedBy { it.hostAddress?.startsWith("169.254") == true }
                .firstOrNull()
                ?.hostAddress
        }
    }
}
