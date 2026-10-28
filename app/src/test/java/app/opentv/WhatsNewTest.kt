/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv

import app.opentv.update.WhatsNew
import com.google.common.truth.Truth.assertThat
import org.junit.Test

/** When the What's new dialog appears after launch, and which versions it covers. */
class WhatsNewTest {

    private val changelog = """
        # Changelog

        ## [0.4.0](https://github.com/neutraltone/settee/compare/v0.3.0...v0.4.0) (2026-12-01)


        ### Features

        * **#20:** pick up where you left off on any series from Home ([aaaaaaa](https://github.com/neutraltone/settee/commit/aaaaaaa))

        ## [0.3.0](https://github.com/neutraltone/settee/compare/v0.2.0...v0.3.0) (2026-11-15)


        ### Bug Fixes

        * **#12:** the guide no longer jumps back to the top after a refresh ([bbbbbbb](https://github.com/neutraltone/settee/commit/bbbbbbb))

        ## [0.2.0](https://github.com/neutraltone/settee/compare/v0.1.0...v0.2.0) (2026-10-28)


        ### Features

        * **#9:** episodes show how far through them you are ([ccccccc](https://github.com/neutraltone/settee/commit/ccccccc))
    """.trimIndent()

    private val installedAt = 1_700_000_000_000L

    @Test
    fun `a fresh install shows nothing`() {
        val notes = WhatsNew.notesToShow(
            installed = "0.4.0",
            lastSeen = null,
            firstInstallTime = installedAt,
            lastUpdateTime = installedAt,
            changelog = changelog,
        )

        assertThat(notes).isEmpty()
    }

    @Test
    fun `an upgrade shows every version since the last one seen`() {
        val notes = WhatsNew.notesToShow(
            installed = "0.4.0",
            lastSeen = "0.2.0",
            firstInstallTime = installedAt,
            lastUpdateTime = installedAt + 1,
            changelog = changelog,
        )

        assertThat(notes.map { it.version }).containsExactly("0.4.0", "0.3.0").inOrder()
    }

    @Test
    fun `relaunching the same version shows nothing`() {
        val notes = WhatsNew.notesToShow(
            installed = "0.4.0",
            lastSeen = "0.4.0",
            firstInstallTime = installedAt,
            lastUpdateTime = installedAt + 1,
            changelog = changelog,
        )

        assertThat(notes).isEmpty()
    }

    @Test
    fun `an upgrade from a build too old to have stored a version shows only the installed version`() {
        val notes = WhatsNew.notesToShow(
            installed = "0.4.0",
            lastSeen = null,
            firstInstallTime = installedAt,
            lastUpdateTime = installedAt + 1,
            changelog = changelog,
        )

        assertThat(notes.map { it.version }).containsExactly("0.4.0")
    }

    @Test
    fun `the installed version's notes are found however its version is written`() {
        val shortVersion = """
            ## [1.0](https://github.com/neutraltone/settee/compare/v0.4.0...v1.0) (2027-01-01)


            ### Features

            * **#50:** a new thing
        """.trimIndent()

        assertThat(WhatsNew.installedNotes(installed = "1.0.0", changelog = shortVersion)).isNotEmpty()
    }
}
