/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv.data.remote

import app.opentv.core.HttpStatus

/**
 * A provider's answer the app understood and refused, with [problem] saying which, so the viewer's
 * sentence comes from that rather than from the message, which is for logs (docs/ERRORS.md).
 */
open class ProviderException(
    message: String,
    cause: Throwable? = null,
    val problem: Problem = Problem.BAD_REPLY,
    /** The HTTP status the provider answered with, for [Problem.HTTP_STATUS]. */
    override val status: Int? = null,
) : Exception(message, cause),
    HttpStatus {
    enum class Problem {
        LOGIN_REJECTED,
        ACCOUNT_INACTIVE,
        BAD_ADDRESS,

        /** A portal that took none of its addresses' handshakes, or has no MAC to give one. */
        DEVICE_REFUSED,
        HTTP_STATUS,

        /** Empty, not JSON, or missing what every panel sends. */
        BAD_REPLY,
    }
}
