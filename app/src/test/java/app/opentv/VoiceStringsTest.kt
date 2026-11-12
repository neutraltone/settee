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
 * Every error and empty state follows Voice in every language (#166). Reads the resource files, as
 * [BrandingStringsTest] does, because a translation that kept the old wording hides in one of ~30.
 */
class VoiceStringsTest {

    private val resDir = File("src/main/res")

    /** Each locale's strings, by folder name ("values", "values-de", ...). */
    private val locales: Map<String, Map<String, String>> by lazy {
        resDir.listFiles { f -> f.name == "values" || f.name.startsWith("values-") }!!
            .map { File(it, "strings.xml") }
            .filter { it.exists() }
            .associate { file ->
                val strings = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(file)
                    .getElementsByTagName("string")
                file.parentFile!!.name to (0 until strings.length).map { strings.item(it) as Element }
                    .associate { it.getAttribute("name") to it.textContent }
            }
    }

    private val english get() = locales.getValue("values")

    /** The keys docs/ERRORS.md lists, which is every failure the app can show. */
    private val errorKeys: Set<String> by lazy {
        val listed = Regex("`([a-z0-9_]+)`").findAll(File("../docs/ERRORS.md").readText()).map { it.groupValues[1] }
        // A row naming a pair as `x_tv` / `_device` stands for both.
        val expanded = listed.flatMap { key -> listOf(key, key.removeSuffix("_tv") + "_device") }
        expanded.filter { it in english }.toSet() + english.keys.filter { it.startsWith("error_") }
    }

    private val emptyStateKeys = setOf(
        "home_empty_hint", "guide_no_channels_title", "guide_add_provider_desc", "guide_no_favourites_hint",
        "guide_no_info", "guide_no_info_channel", "vod_no_movies_provider", "vod_no_movies_add",
        "vod_no_shows_provider", "vod_no_shows_add", "vod_empty_category_movies", "vod_empty_category_shows",
        "channels_manager_empty_category", "channels_manager_no_channels", "channels_manager_no_match",
        "search_no_results", "rec_empty_state", "providers_empty", "addons_empty", "parental_no_categories",
        "kbd_clipboard_empty",
    )

    private val voiceKeys get() = errorKeys + emptyStateKeys

    /**
     * Untranslated in every language before #166, and unchanged by it: the ticket leaves translation
     * gaps it didn't make to their own work, so these fall back to English for now.
     */
    private val untranslatedBefore =
        setOf("about_check_failed", "playback_try_other_quality", "vod_empty_category_shows")

    @Test
    fun `docs ERRORS md names only strings that exist, and lists the playback and provider sentences`() {
        assertWithMessage("error keys found in docs/ERRORS.md").that(errorKeys).containsAtLeast(
            "error_http_401", "error_http_403", "error_gone", "error_cant_reach_tv", "error_cant_reach_device",
            "error_login", "sync_wrong_code", "recset_status_failed", "about_check_failed",
        )
    }

    @Test
    fun `every error and empty state has a translation in every language`() {
        val missing = locales.filterKeys { it != "values" }.flatMap { (locale, strings) ->
            (voiceKeys - untranslatedBefore).filter { it !in strings }.map { "$locale/$it" }
        }

        assertWithMessage("errors and empty states with no translation").that(missing).isEmpty()
    }

    @Test
    fun `no error or empty state has an em dash or an exclamation mark, in any language`() {
        val offenders = locales.flatMap { (locale, strings) ->
            voiceKeys.mapNotNull { key ->
                strings[key]?.takeIf { Regex("[—–!！]").containsMatchIn(it) }?.let { "$locale/$key: $it" }
            }
        }

        assertWithMessage("strings breaking Voice").that(offenders).isEmpty()
    }

    @Test
    fun `no English error sentence carries a code, a status or a setup term`() {
        val offenders = errorKeys.mapNotNull { key ->
            english.getValue(key).takeIf {
                Regex("""\(\d{3}\)|HTTP|ERROR_CODE|User-Agent|\bserver\b|\bMAC\b|Xtream|M3U""").containsMatchIn(it)
            }
                ?.let { "$key: $it" }
        }

        assertWithMessage("error sentences with codes or setup terms").that(offenders).isEmpty()
    }

    @Test
    fun `every translation keeps the English placeholders`() {
        val placeholder = Regex("""%\d\$[sd]""")
        val offenders = locales.filterKeys { it != "values" }.flatMap { (locale, strings) ->
            voiceKeys.mapNotNull { key ->
                val wanted = placeholder.findAll(english.getValue(key)).map { it.value }.toSet()
                val found = strings[key]?.let { text -> placeholder.findAll(text).map { it.value }.toSet() }
                if (found != null && found != wanted) "$locale/$key" else null
            }
        }

        assertWithMessage("translations missing a placeholder").that(offenders).isEmpty()
    }
}
