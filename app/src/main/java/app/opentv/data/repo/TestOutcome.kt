/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv.data.repo

import android.content.Context
import app.opentv.R
import app.opentv.core.LocaleUtils
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

/** What Test connection found when a provider's details work. Failures travel as exceptions instead. */
sealed interface TestOutcome {
    /** An Xtream login the panel accepted, with what it said about the account, where it said it. */
    data class Xtream(val username: String?, val maxConnections: Int?, val expiresAtMillis: Long?) : TestOutcome

    /** A Stalker portal that took the MAC address's handshake. */
    data object StalkerAccepted : TestOutcome

    /** An address that answered with the start of a playlist. */
    data object PlaylistFound : TestOutcome
}

/**
 * [TestOutcome] in the viewer's words. On the TV, so the phone's channel manager is sent the same
 * sentence rather than wording it again in a language the TV's viewer didn't choose.
 */
class TestOutcomeWording(private val context: Context) {
    // Looked up each time, as RefreshMessages does: changing language only recreates the activity.
    private val resources get() = LocaleUtils.wrap(context).resources

    fun of(outcome: TestOutcome): String = when (outcome) {
        is TestOutcome.Xtream -> xtream(outcome)
        TestOutcome.StalkerAccepted -> resources.getString(R.string.test_ok_stalker)
        TestOutcome.PlaylistFound -> resources.getString(R.string.test_ok_playlist)
    }

    private fun xtream(outcome: TestOutcome.Xtream): String {
        val res = resources
        return listOfNotNull(
            res.getString(R.string.test_ok_xtream),
            outcome.username?.let { res.getString(R.string.test_ok_xtream_user, it) },
            outcome.maxConnections?.let { res.getQuantityString(R.plurals.test_ok_xtream_connections, it, it) },
            outcome.expiresAtMillis?.let { res.getString(R.string.test_ok_xtream_expires, date(it)) },
        ).joinToString(" ")
    }

    // The app's language decides how the date reads, as it does the words around it; java.text's
    // DateFormat would follow the device's instead.
    private fun date(utcMillis: Long): String = DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM)
        .withLocale(resources.configuration.locales[0])
        .withZone(ZoneId.systemDefault())
        .format(Instant.ofEpochMilli(utcMillis))
}
