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
 * The guide's rows hold the day's programme index for as long as anything collects them, so every
 * screen collects them only while it's started (#327). Settings, Search and title pages keep the main
 * screen built under them: a plain `collectAsState` there would keep the index, and regroup it on
 * every EPG write, behind them for as long as they're open, with nothing on screen to show it.
 */
class GuideCollectionTest {

    private val sourceDir = File("src/main/java/app/opentv")

    @Test
    fun `the guide's rows are collected only while the screen showing them is started`() {
        val uses = sourceDir.walk()
            .filter { it.extension == "kt" && it.name != "OpenTvViewModels.kt" }
            .flatMap { file -> USE.findAll(file.readText()).map { file.name to it.value } }
            .toList()
        assertThat(uses).isNotEmpty()
        uses.forEach { (file, use) ->
            assertWithMessage(file).that(use).endsWith(".collectAsStateWithLifecycle")
        }
    }

    private companion object {
        /** `guideRows` and the call made on it, however the line wraps. */
        val USE = Regex("""\.guideRows\s*\.\s*\w+""")
    }
}
