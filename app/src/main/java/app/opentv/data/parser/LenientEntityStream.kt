/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv.data.parser

import java.io.InputStream

/**
 * Rewrites the `&`s of a guide file that strict XML would stop at, as the bytes stream past (#347).
 *
 * Guide files are written by scrapers, and plenty carry a bare `&` (`Tom & Jerry`) or an HTML
 * entity XML never defined (`&nbsp;`, `&eacute;`). KXmlParser stops at the first one, on every
 * sync, and every channel after it loses its listings. So before the parser sees them:
 * - a bare `&` becomes `&amp;`;
 * - an HTML entity becomes its numeric reference (`&eacute;` → `&#233;`);
 * - any other named entity becomes literal text (`&foo;` → `&amp;foo;`), as does a numeric
 *   reference to a character XML forbids;
 * - XML's own five entities, good numeric references and CDATA sections pass through untouched.
 *
 * Why not KXmlParser's relaxed mode with the HTML entities defined on it: relaxed also lets
 * through mismatched and unclosed tags, so a guide cut off part way could look like it ended,
 * and the caller would prune programmes it never got the replacements for. This keeps the
 * parser strict about structure and lenient only about entities.
 *
 * It works on bytes, not characters, so the parser still reads the file's declared encoding:
 * `&`, `;`, `<` and entity names are ASCII, which every encoding a guide uses (UTF-8 and the
 * ISO-8859 family) writes as the same single bytes, and a numeric reference means the same
 * character whatever the encoding. (A UTF-16 guide would come through mangled; none is known.)
 * It never holds more than a buffer of the file: guides reach about 150 MB.
 */
internal class LenientEntityStream(private val source: InputStream) : InputStream() {

    private val buffer = ByteArray(BUFFER_SIZE)
    private var position = 0
    private var limit = 0
    private var ended = false

    private var pending: ByteArray = EMPTY
    private var pendingPosition = 0

    private var inCdata = false
    private var closingBrackets = 0

    override fun read(): Int {
        val one = ByteArray(1)
        return if (read(one, 0, 1) == -1) -1 else one[0].toInt() and 0xFF
    }

    override fun read(b: ByteArray, off: Int, len: Int): Int {
        if (len == 0) return 0
        var written = 0
        while (written < len) {
            if (pendingPosition < pending.size) {
                val n = minOf(len - written, pending.size - pendingPosition)
                System.arraycopy(pending, pendingPosition, b, off + written, n)
                pendingPosition += n
                written += n
                continue
            }
            if (available(1) == 0) break
            val c = buffer[position]
            when {
                inCdata -> {
                    position++
                    b[off + written++] = c
                    trackCdataEnd(c)
                }
                c == LT && startsWith(CDATA_OPEN) -> {
                    position += CDATA_OPEN.size
                    queue(CDATA_OPEN)
                    inCdata = true
                    closingBrackets = 0
                }
                c == AMP -> queue(reference())
                else -> {
                    position++
                    b[off + written++] = c
                }
            }
        }
        return if (written == 0) -1 else written
    }

    override fun close() = source.close()

    private fun trackCdataEnd(c: Byte) {
        closingBrackets = when {
            c == RSQB -> closingBrackets + 1
            c == GT && closingBrackets >= 2 -> {
                inCdata = false
                0
            }
            else -> 0
        }
    }

    private fun queue(bytes: ByteArray) {
        pending = bytes
        pendingPosition = 0
    }

    /** Consumes the `&` at [position], and the reference after it if there's a whole one. */
    private fun reference(): ByteArray {
        // Before reading position: making the bytes readable may move them to the buffer's start.
        val readable = available(MAX_REFERENCE)
        val end = position + readable
        var i = position + 1
        while (i < end && buffer[i].isReferenceChar()) i++
        if (i == position + 1 || i >= end || buffer[i] != SEMICOLON) {
            position++
            return ESCAPED_AMP
        }
        val name = String(buffer, position + 1, i - position - 1, Charsets.US_ASCII)
        position = i + 1
        val replacement = when {
            name.startsWith('#') -> if (isAllowedCharacterReference(name)) "&$name;" else "&amp;$name;"
            name in XML_ENTITIES -> "&$name;"
            else -> HTML_ENTITIES[name]?.let { "&#$it;" } ?: "&amp;$name;"
        }
        return replacement.toByteArray(Charsets.US_ASCII)
    }

    private fun isAllowedCharacterReference(name: String): Boolean {
        val code = when {
            name.startsWith("#x") || name.startsWith("#X") -> name.drop(2).toIntOrNull(HEX)
            else -> name.drop(1).toIntOrNull()
        } ?: return false
        return code == 0x9 || code == 0xA || code == 0xD || code in 0x20..0xD7FF ||
            code in 0xE000..0xFFFD || code in 0x10000..0x10FFFF
    }

    private fun startsWith(prefix: ByteArray): Boolean {
        if (available(prefix.size) < prefix.size) return false
        return prefix.indices.all { buffer[position + it] == prefix[it] }
    }

    /** Makes up to [wanted] bytes readable from [position] in one run; returns how many are. */
    private fun available(wanted: Int): Int {
        if (limit - position < wanted && !ended) {
            if (position > 0) {
                System.arraycopy(buffer, position, buffer, 0, limit - position)
                limit -= position
                position = 0
            }
            while (limit < wanted) {
                val n = source.read(buffer, limit, buffer.size - limit)
                if (n == -1) {
                    ended = true
                    break
                }
                limit += n
            }
        }
        return minOf(wanted, limit - position)
    }

    private fun Byte.isReferenceChar(): Boolean {
        val c = toInt().toChar()
        return c in 'a'..'z' || c in 'A'..'Z' || c in '0'..'9' || c == '#'
    }

    private companion object {
        const val BUFFER_SIZE = 8 * 1024

        /** Longer than any entity name or numeric reference; past it, an `&` is bare. */
        const val MAX_REFERENCE = 34
        const val HEX = 16

        const val AMP = '&'.code.toByte()
        const val LT = '<'.code.toByte()
        const val GT = '>'.code.toByte()
        const val RSQB = ']'.code.toByte()
        const val SEMICOLON = ';'.code.toByte()

        val EMPTY = ByteArray(0)
        val ESCAPED_AMP = "&amp;".toByteArray(Charsets.US_ASCII)
        val CDATA_OPEN = "<![CDATA[".toByteArray(Charsets.US_ASCII)

        val XML_ENTITIES = setOf("amp", "lt", "gt", "quot", "apos")

        /** HTML's Latin-1 entities, U+00A0 to U+00FF in order. */
        private const val LATIN_1 = "nbsp iexcl cent pound curren yen brvbar sect uml copy ordf laquo not " +
            "shy reg macr deg plusmn sup2 sup3 acute micro para middot cedil sup1 ordm raquo frac14 frac12 " +
            "frac34 iquest Agrave Aacute Acirc Atilde Auml Aring AElig Ccedil Egrave Eacute Ecirc Euml Igrave " +
            "Iacute Icirc Iuml ETH Ntilde Ograve Oacute Ocirc Otilde Ouml times Oslash Ugrave Uacute Ucirc Uuml " +
            "Yacute THORN szlig agrave aacute acirc atilde auml aring aelig ccedil egrave eacute ecirc euml " +
            "igrave iacute icirc iuml eth ntilde ograve oacute ocirc otilde ouml divide oslash ugrave uacute " +
            "ucirc uuml yacute thorn yuml"

        val HTML_ENTITIES: Map<String, Int> =
            LATIN_1.split(' ').withIndex().associate { (i, name) -> name to 0xA0 + i } +
            mapOf(
                "OElig" to 0x152, "oelig" to 0x153, "Scaron" to 0x160, "scaron" to 0x161, "Yuml" to 0x178,
                "ndash" to 0x2013, "mdash" to 0x2014, "lsquo" to 0x2018, "rsquo" to 0x2019, "sbquo" to 0x201A,
                "ldquo" to 0x201C, "rdquo" to 0x201D, "bdquo" to 0x201E, "bull" to 0x2022, "hellip" to 0x2026,
                "prime" to 0x2032, "euro" to 0x20AC, "trade" to 0x2122,
            )
    }
}
