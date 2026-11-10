/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import java.io.File
import java.security.MessageDigest
import org.junit.Test

/**
 * The fonts ship exactly as published, never subset or edited (docs/LOOK.md, Shipping the fonts),
 * and each sits beside the record of where it came from. The hashes in FONTS.md were taken from
 * google/fonts when the files were added.
 */
class FontFilesTest {

    private val main = File("src/main")
    private val record = File(main, "assets/licences/FONTS.md")

    /** `path` to SHA-256, from FONTS.md's table rows. */
    private val row = Regex("""^\| `([^`]+)` \|.*\| `([0-9a-f]{64})` \|$""", RegexOption.MULTILINE)
    private val recorded: Map<String, String> = row
        .findAll(record.readText())
        .associate { it.groupValues[1] to it.groupValues[2] }

    @Test
    fun `every bundled font and licence matches its recorded hash`() {
        assertThat(recorded).hasSize(5)
        recorded.forEach { (path, hash) ->
            assertWithMessage(path).that(sha256(File(main, path))).isEqualTo(hash)
        }
    }

    @Test
    fun `every font in the app is recorded`() {
        val fonts = File(main, "res/font").listFiles().orEmpty().map { "res/font/${it.name}" }
        assertThat(fonts).isNotEmpty()
        assertThat(recorded.keys).containsAtLeastElementsIn(fonts)
    }

    private fun sha256(file: File): String =
        MessageDigest.getInstance("SHA-256").digest(file.readBytes()).joinToString("") { "%02x".format(it) }
}
