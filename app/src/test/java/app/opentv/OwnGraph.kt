/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import app.opentv.core.ServiceLocator
import org.junit.rules.ExternalResource

/**
 * A graph and database of its own for each test, standing in for the app's wherever a screen asks
 * [ServiceLocator] for it. The process's graph outlives every test class run before this one, with
 * their providers, guides, and a refresh's per-provider locks; deleting the providers isn't enough,
 * since the next one saved is given the same id and waits on a lock a refresh before it still holds.
 * Give it `order = 0`, so it's in place before the screen is composed and closed after it's gone.
 */
class OwnGraph(private val name: String) : ExternalResource() {
    private val app: Application = ApplicationProvider.getApplicationContext()
    private lateinit var catalogue: HeldCatalogue
    private var before: ServiceLocator.Graph? = null

    val graph: ServiceLocator.Graph get() = catalogue.graph

    override fun before() {
        catalogue = HeldCatalogue(app, name)
        before = ServiceLocator.swap(catalogue.graph)
    }

    override fun after() {
        ServiceLocator.swap(before)
        catalogue.close()
    }
}
