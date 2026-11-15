/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv

import app.opentv.data.model.Programme
import app.opentv.data.parser.XmltvParser
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * What the parser makes of the entities real guide files carry (#347). Run through
 * `Xml.newPullParser()` under Robolectric, the same KXmlParser the app gets, because how
 * strict it is about entities is exactly what's being tested.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class XmltvParserTest {

    private data class Parsed(val aliases: List<Pair<String, String?>>, val programmes: List<Programme>)

    private suspend fun parse(xml: String): Parsed {
        val aliases = mutableListOf<Pair<String, String?>>()
        val programmes = mutableListOf<Programme>()
        XmltvParser.parse(
            input = xml.byteInputStream(),
            feedId = 1L,
            onChannelAlias = { id, name -> aliases += id to name },
            onProgramme = { programmes += it },
        )
        return Parsed(aliases, programmes)
    }

    private fun programme(channel: String, title: String, desc: String = "") =
        """<programme start="20260101100000 +0000" stop="20260101110000 +0000" channel="$channel">""" +
            "<title>$title</title><desc>$desc</desc></programme>"

    @Test
    fun `a guide with bare ampersands and HTML entities reads in full`() = runTest {
        val result = parse(
            "<tv>" +
                """<channel id="sky.uk"><display-name>Sky Sports News & More</display-name></channel>""" +
                """<channel id="itv.uk"><display-name>ITV1</display-name></channel>""" +
                programme("sky.uk", "Tom & Jerry", "Caf&eacute;&nbsp;culture") +
                programme("itv.uk", "News") +
                "</tv>",
        )

        assertThat(result.aliases).containsExactly(
            "sky.uk" to "Sky Sports News & More",
            "itv.uk" to "ITV1",
        ).inOrder()
        assertThat(result.programmes.map { it.epgChannelId to it.title }).containsExactly(
            "sky.uk" to "Tom & Jerry",
            "itv.uk" to "News",
        ).inOrder()
        assertThat(result.programmes.first().description).isEqualTo("Café culture")
    }

    @Test
    fun `a bare ampersand in an attribute is read as itself`() = runTest {
        val result = parse(
            """<tv><channel id="news&sport.uk"><display-name>News</display-name></channel></tv>""",
        )

        assertThat(result.aliases).containsExactly("news&sport.uk" to "News")
    }

    @Test
    fun `entities XML defines and numeric references still decode`() = runTest {
        val result = parse(
            "<tv>" +
                programme("c1", "&lt;Live&gt; &amp; &quot;Loud&quot; &apos;n&apos;", "&#233;&#xE9;&#8212;") +
                "</tv>",
        )

        assertThat(result.programmes.single().title).isEqualTo("<Live> & \"Loud\" 'n'")
        assertThat(result.programmes.single().description).isEqualTo("éé—")
    }

    @Test
    fun `an entity nobody defines doesn't stop the guide`() = runTest {
        val result = parse(
            "<tv>" + programme("c1", "Odd &foo; title") + programme("c2", "After") + "</tv>",
        )

        assertThat(result.programmes.map { it.title }).containsExactly("Odd &foo; title", "After").inOrder()
    }

    /** Long enough that entities and bare `&`s fall across where the file is read in pieces. */
    @Test
    fun `entities are decoded all through a long guide`() = runTest {
        val channels = (1..2_000).joinToString("") { n ->
            """<channel id="c$n.fr"><display-name>T&eacute;l&eacute; $n & Co</display-name></channel>"""
        }

        val result = parse("<tv>$channels</tv>")

        assertThat(result.aliases).hasSize(2_000)
        assertThat(result.aliases.map { it.second }.toSet())
            .isEqualTo((1..2_000).map { "Télé $it & Co" }.toSet())
    }

    /** A guide's CDATA is already literal: escaping its `&` would show the viewer `&amp;`. */
    @Test
    fun `an ampersand inside CDATA is left as it is`() = runTest {
        val result = parse(
            "<tv>" + programme("c1", "<![CDATA[Tom & Jerry]]>", "<![CDATA[Fish &amp; chips]]>") + "</tv>",
        )

        assertThat(result.programmes.single().title).isEqualTo("Tom & Jerry")
        assertThat(result.programmes.single().description).isEqualTo("Fish &amp; chips")
    }
}
