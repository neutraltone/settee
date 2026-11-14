/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.yield
import org.junit.Test

class FirstOpenWorkTest {

    private val ran = mutableListOf<String>()

    private fun work(
        nasAutoSync: Boolean,
        syncNas: suspend () -> Unit = { ran += "nas" },
        catchUpNormaliser: suspend () -> Unit = { ran += "normaliser" },
    ) = FirstOpenWork(
        nasAutoSync = nasAutoSync,
        syncNas = syncNas,
        catchUpNormaliser = catchUpNormaliser,
        launchRefresh = { ran += "refresh" },
    )

    @Test
    fun `with NAS auto-sync off, opening the app leaves the NAS alone`() = runTest {
        work(nasAutoSync = false).run()

        assertThat(ran).containsExactly("normaliser", "refresh")
    }

    @Test
    fun `with NAS auto-sync on, opening the app syncs the NAS too`() = runTest {
        work(nasAutoSync = true).run()

        assertThat(ran).containsExactly("nas", "normaliser", "refresh")
    }

    @Test
    fun `the launch refresh starts only once the normaliser has caught up`() = runTest {
        work(
            nasAutoSync = false,
            catchUpNormaliser = {
                yield()
                ran += "normaliser"
            },
        ).run()

        assertThat(ran).containsExactly("normaliser", "refresh").inOrder()
    }

    @Test
    fun `a catalogue that fails to catch up doesn't stop the NAS sync`() = runTest {
        val failing = work(
            nasAutoSync = true,
            syncNas = {
                yield()
                ran += "nas"
            },
            catchUpNormaliser = { error("database unavailable") },
        )

        failing.run()

        assertThat(ran).contains("nas")
    }

    @Test
    fun `a catalogue that fails to catch up still gets its launch refresh, and nothing reaches the app`() = runTest {
        val escaped = mutableListOf<Throwable>()
        val failing = work(nasAutoSync = false, catchUpNormaliser = { error("database full") })

        withContext(CoroutineExceptionHandler { _, t -> escaped += t }) { failing.run() }

        assertThat(ran).containsExactly("refresh")
        assertThat(escaped).isEmpty()
    }

    @Test
    fun `a launch refresh that fails doesn't reach the app`() = runTest {
        val escaped = mutableListOf<Throwable>()
        val failing = FirstOpenWork(
            nasAutoSync = false,
            syncNas = {},
            catchUpNormaliser = { ran += "normaliser" },
            launchRefresh = { error("database full") },
        )

        withContext(CoroutineExceptionHandler { _, t -> escaped += t }) { failing.run() }

        assertThat(ran).containsExactly("normaliser")
        assertThat(escaped).isEmpty()
    }
}
