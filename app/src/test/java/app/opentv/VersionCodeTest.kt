/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv

import com.google.common.truth.Truth.assertWithMessage
import org.junit.Test

/**
 * A release bumps `appVersionName` only; Gradle derives `versionCode` from it as
 * major×10000 + minor×100 + patch. A hand-edited `versionCode` that drifts from the name would
 * let Android treat a newer release as a downgrade and refuse to install it.
 */
class VersionCodeTest {

    @Test
    fun `the version code is worked out from the version name`() {
        val (major, minor, patch) = BuildConfig.VERSION_NAME.split('.').map { it.toInt() }

        assertWithMessage("versionCode for ${BuildConfig.VERSION_NAME}")
            .that(BuildConfig.VERSION_CODE)
            .isEqualTo(major * 10000 + minor * 100 + patch)
    }
}
