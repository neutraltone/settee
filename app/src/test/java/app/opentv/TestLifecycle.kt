/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv

import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry

/**
 * A screen's lifecycle that a test moves by hand, as the app going behind another one and coming back
 * does. Provide it as `LocalLifecycleOwner`; it starts in front, as the test rule's own does.
 */
class TestLifecycle : LifecycleOwner {
    private val registry = LifecycleRegistry(this).apply { currentState = Lifecycle.State.RESUMED }

    override val lifecycle: Lifecycle get() = registry

    /** Behind another app: the activity is stopped. */
    fun goBehind() {
        registry.currentState = Lifecycle.State.CREATED
    }

    /** In front again. */
    fun comeBack() {
        registry.currentState = Lifecycle.State.RESUMED
    }
}
