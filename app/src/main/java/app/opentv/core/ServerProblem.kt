/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv.core

/**
 * Why one of the small servers this device runs for a phone or another device (sharing, pairing, the
 * channel manager) stopped. Each screen words it for what it was doing (docs/ERRORS.md).
 */
enum class ServerProblem {
    /** No LAN address, so nothing could reach it. */
    NO_NETWORK,

    /** The socket wouldn't open. */
    COULDNT_START,

    /** Nobody came, or nobody stayed, for long enough. */
    TIMED_OUT,

    /** A wrong code too many times. */
    TOO_MANY_CODES,
}
