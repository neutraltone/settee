/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv

import com.google.common.truth.Truth.assertWithMessage
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Test
import org.w3c.dom.Element

/**
 * The viewer sees Settee in every language. Reads the resource files rather than going through
 * Robolectric's `Resources`, because a missed rename usually hides in one of ~30 translations
 * and checking a single locale at runtime would never notice.
 */
class BrandingStringsTest {

    private val resDir = File("src/main/res")

    /** The one string that is meant to name OpenTV: the credit on the About screen. */
    private val creditKeys = setOf("about_based_on")

    @Test
    fun `no user-facing string in any language says OpenTV`() {
        val files = resDir.listFiles { f -> f.name == "values" || f.name.startsWith("values-") }!!
            .map { File(it, "strings.xml") }
            .filter { it.exists() }
        assertWithMessage("string resource files found").that(files.size).isGreaterThan(1)

        val offenders = files.flatMap { file ->
            val doc = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(file)
            val strings = doc.getElementsByTagName("string")
            (0 until strings.length).map { strings.item(it) as Element }
                .filter { it.getAttribute("name") !in creditKeys }
                .filter { it.textContent.contains("OpenTV") }
                .map { "${file.parentFile.name}/${it.getAttribute("name")}" }
        }

        assertWithMessage("strings still naming OpenTV").that(offenders).isEmpty()
    }

    @Test
    fun `the launcher name is Settee`() {
        val doc = DocumentBuilderFactory.newInstance().newDocumentBuilder()
            .parse(File(resDir, "values/strings.xml"))
        val strings = doc.getElementsByTagName("string")
        val appName = (0 until strings.length).map { strings.item(it) as Element }
            .single { it.getAttribute("name") == "app_name" }.textContent

        assertWithMessage("app_name").that(appName).isEqualTo("Settee")
    }
}
