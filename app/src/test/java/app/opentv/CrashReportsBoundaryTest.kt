/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import java.io.File
import org.junit.Test

/**
 * Only `core/CrashReports` and its own helpers touch Sentry's SDK (ADR 0009), so what a Crash Report
 * may hold, and when one may leave, is decided in one place. Screens, view models and the rest go
 * through `CrashReports`.
 */
class CrashReportsBoundaryTest {

    private val sources = File("src/main/java")

    @Test
    fun `nothing but CrashReports and its helpers uses Sentry`() {
        val users = sources.walk()
            .filter { it.extension == "kt" }
            // The package name anywhere, not just in imports: a fully qualified use crosses too.
            .filter { it.readText().contains("io.sentry") }
            .map { it.relativeTo(sources).path }
            .toList()
        assertThat(users).isNotEmpty()
        users.forEach { path -> assertWithMessage(path).that(path).isIn(ALLOWED) }
    }

    private companion object {
        val ALLOWED = setOf(
            "app/opentv/core/CrashReports.kt",
            "app/opentv/core/CrashScrubbing.kt",
            "app/opentv/core/HeldCrash.kt",
        )
    }
}
