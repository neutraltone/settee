/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv.data.repo

/** What Test connection found when a provider's details work. Failures travel as exceptions instead. */
sealed interface TestOutcome {
    /** An Xtream login the panel accepted, with what it said about the account, where it said it. */
    data class Xtream(val username: String?, val maxConnections: Int?, val expiresAtMillis: Long?) : TestOutcome

    /** A Stalker portal that took the MAC address's handshake. */
    data object StalkerAccepted : TestOutcome

    /** An address that answered with the start of a playlist. */
    data object PlaylistFound : TestOutcome
}
