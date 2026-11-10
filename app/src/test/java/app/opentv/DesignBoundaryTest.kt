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
 * `ui/design` takes plain inputs and slots, so it never knows about channels, titles or providers
 * (#147). Anything that needs the data model, like a watch badge, is built outside on top of it.
 */
class DesignBoundaryTest {

    private val designDir = File("src/main/java/app/opentv/ui/design")

    @Test
    fun `the design system never reaches into the data layer`() {
        val sources = designDir.walk().filter { it.extension == "kt" }.toList()
        assertThat(sources).isNotEmpty()
        sources.forEach { file ->
            // The package name anywhere, not just in imports: a fully qualified use crosses too.
            assertWithMessage(file.name).that(file.readText()).doesNotContain("app.opentv.data")
        }
    }
}
