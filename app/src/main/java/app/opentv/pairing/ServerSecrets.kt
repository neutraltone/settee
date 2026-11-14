/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv.pairing

import androidx.annotation.VisibleForTesting
import java.io.IOException
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.security.SecureRandom
import java.util.Random

/**
 * Where the servers a phone or another device reaches ([PhoneAddress], [PairingServer],
 * `SyncServer`) get their port, tokens and codes.
 *
 * The app always gets a free port from the OS and secrets from [SecureRandom]. Screenshot tests set
 * [fixed], because the address, QR code and code are drawn on screen, and drawn differently every
 * run they showed up as changed on every PR's screenshot table.
 */
object ServerSecrets {
    /** A port to ask for first and a seed for the secrets, or null for the random ones. */
    data class Fixed(val port: Int, val seed: Long)

    @VisibleForTesting
    @Volatile
    var fixed: Fixed? = null

    private val secure = SecureRandom()

    /**
     * Where one server's secrets come from. Fixed, each server gets a fresh generator, so what it
     * draws doesn't depend on which tests ran before it.
     */
    fun random(): Random = fixed?.let { Random(it.seed) } ?: secure

    /**
     * A listening socket. Port 0 asks the OS for a free one: a fixed port would be predictable and
     * would clash with whatever else the user runs at home. Fixed, a port something else holds
     * falls back to a free one.
     */
    @Throws(IOException::class)
    fun openSocket(): ServerSocket {
        val port = fixed?.port ?: return ServerSocket(0)
        val socket = ServerSocket()
        return try {
            socket.reuseAddress = true
            socket.bind(InetSocketAddress(port))
            socket
        } catch (_: IOException) {
            socket.close()
            ServerSocket(0)
        }
    }

    /** A six-digit code to type on the other device. */
    fun sixDigitCode(random: Random = random()): String = "%06d".format(random.nextInt(SIX_DIGITS))

    private const val SIX_DIGITS = 1_000_000
}
