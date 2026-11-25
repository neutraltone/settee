/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv.core

import android.content.res.Resources
import app.opentv.R
import app.opentv.data.repo.TestOutcome
import java.time.ZoneId

/**
 * [TestOutcome] in the viewer's words, from [ErrorWording.tests], which words the same Test's failures.
 * On the TV, so the phone's channel manager is sent the same sentence rather than wording it again in
 * a language the TV's viewer didn't choose. Dates are in [zone], the viewer's.
 */
class TestOutcomeWording internal constructor(private val strings: () -> Resources, private val zone: () -> ZoneId) {
    private val resources get() = strings()

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
    private fun date(utcMillis: Long): String =
        ViewerTime.localDate(utcMillis, zone(), resources.configuration.locales[0])
}
