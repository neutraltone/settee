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
 * - XML's own five entities, good numeric references, CDATA sections and comments pass through
 *   untouched.
 *
 * Why not KXmlParser's relaxed mode with the HTML entities defined on it: relaxed also lets
 * through mismatched and unclosed tags, so a guide cut off part way could look like it ended,
 * and the caller would prune programmes it never got the replacements for. This keeps the
 * parser strict about structure and lenient only about entities.
 *
 * Entities a guide declares in its own DOCTYPE come through as literal text too. KXmlParser
 * never resolved them anyway (it would only with DOCTYPE processing on, which also lets a hostile
 * guide expand entities until the Stick runs out of memory), and before this it stopped at them.
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

    /** Bytes from [position] on to hand over as they are: a reference or section opener let through. */
    private var verbatim = 0

    /** A replacement waiting to be handed over before anything more of [buffer]. */
    private var pending: ByteArray = EMPTY
    private var pendingPosition = 0

    /** Inside CDATA or a comment: `]` or `-`, the character that closes it twice before `>`. */
    private var sectionCloser: Byte? = null
    private var closersSeen = 0

    private val oneByte = ByteArray(1)

    override fun read(): Int = if (read(oneByte, 0, 1) == -1) -1 else oneByte[0].toInt() and BYTE_MASK

    override fun read(b: ByteArray, off: Int, len: Int): Int {
        var written = 0
        while (written < len) {
            // Hand over what's ready rather than wait on the network for the rest of [len].
            val ready = pendingPosition < pending.size || position < limit || (written == 0 && fill(1) > 0)
            if (!ready) break
            written += step(b, off + written, len - written)
        }
        return if (written == 0 && len > 0) -1 else written
    }

    /** Hands over up to [room] bytes at [b]'s [at], or none while it rewrites a reference; returns how many. */
    private fun step(b: ByteArray, at: Int, room: Int): Int = when {
        pendingPosition < pending.size -> {
            val n = minOf(room, pending.size - pendingPosition)
            System.arraycopy(pending, pendingPosition, b, at, n)
            pendingPosition += n
            n
        }
        verbatim > 0 -> {
            val n = copyRun(b, at, minOf(room, verbatim))
            verbatim -= n
            n
        }
        sectionCloser != null -> {
            trackSectionEnd(buffer[position])
            copyRun(b, at, 1)
        }
        buffer[position] == LT -> {
            openTag()
            0
        }
        buffer[position] == AMP -> {
            reference()
            0
        }
        else -> {
            // Up to the next byte that needs a look, in one copy.
            var end = position + 1
            val stop = minOf(limit, position + room)
            while (end < stop && buffer[end] != AMP && buffer[end] != LT) end++
            copyRun(b, at, end - position)
        }
    }

    override fun available(): Int = (pending.size - pendingPosition) + (limit - position)

    override fun close() = source.close()

    /** Copies up to [max] bytes of [buffer] from [position] to [b] as they are; returns how many. */
    private fun copyRun(b: ByteArray, at: Int, max: Int): Int {
        val n = minOf(max, limit - position)
        System.arraycopy(buffer, position, b, at, n)
        position += n
        return n
    }

    /** At a `<`: lets it through, and notes when it opens CDATA or a comment, where `&` is just text. */
    private fun openTag() {
        fun opens(prefix: ByteArray) = fill(prefix.size) == prefix.size && buffer.isAt(position, prefix.size, prefix)
        sectionCloser = when {
            opens(CDATA_OPEN) -> RSQB.also { verbatim = CDATA_OPEN.size }
            opens(COMMENT_OPEN) -> DASH.also { verbatim = COMMENT_OPEN.size }
            else -> null.also { verbatim = 1 }
        }
        closersSeen = 0
    }

    /** Called with each byte inside a section, before it's handed over. */
    private fun trackSectionEnd(c: Byte) {
        closersSeen = when {
            c == sectionCloser -> closersSeen + 1
            c == GT && closersSeen >= 2 -> {
                sectionCloser = null
                0
            }
            else -> 0
        }
    }

    /** At an `&`: lets a reference XML knows through, and rewrites anything else. */
    private fun reference() {
        fun replace(consumed: Int, replacement: ByteArray) {
            position += consumed
            pending = replacement
            pendingPosition = 0
        }
        // Before reading position: making the bytes readable may move them to the buffer's start.
        val readable = fill(MAX_REFERENCE)
        val end = position + readable
        val nameStart = position + 1
        var i = nameStart
        while (i < end && buffer[i].isReferenceChar()) i++
        val nameLength = i - nameStart
        val whole = nameLength > 0 && i < end && buffer[i] == SEMICOLON
        val numeric = whole && buffer[nameStart] == HASH
        // The & and ; around the name.
        val referenceLength = nameLength + 2
        when {
            !whole -> replace(1, ESCAPED_AMP)
            numeric && buffer.isAllowedCharacterReference(nameStart, nameLength) -> verbatim = referenceLength
            numeric -> replace(1, ESCAPED_AMP)
            XML_ENTITIES.any { buffer.isAt(nameStart, nameLength, it) } -> verbatim = referenceLength
            else -> {
                val html = HTML_REFERENCES[String(buffer, nameStart, nameLength, Charsets.US_ASCII)]
                // Unknown: only the & is rewritten; the name and ; after it are ordinary text.
                if (html == null) replace(1, ESCAPED_AMP) else replace(referenceLength, html)
            }
        }
    }

    /** Makes up to [wanted] bytes readable from [position] in one run; returns how many are. */
    private fun fill(wanted: Int): Int {
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
}

private fun ByteArray.isAt(start: Int, length: Int, name: ByteArray): Boolean =
    length == name.size && name.indices.all { this[start + it] == name[it] }

/** `#233` or `#xE9` at [start], naming a character XML allows in a document. */
private fun ByteArray.isAllowedCharacterReference(start: Int, length: Int): Boolean {
    val hex = length > 1 && (this[start + 1] == LOWER_X || this[start + 1] == UPPER_X)
    val radix = if (hex) HEX else DECIMAL
    val digits = (start + if (hex) 2 else 1) until start + length
    var code = 0
    var valid = !digits.isEmpty()
    for (j in digits) {
        val digit = Character.digit(this[j].toInt(), radix)
        valid = digit >= 0 && code <= MAX_CODE_POINT
        if (!valid) break
        code = code * radix + digit
    }
    return valid && code <= MAX_CODE_POINT && (code in ALLOWED_CONTROLS || ALLOWED_RANGES.any { code in it })
}

private fun Byte.isReferenceChar(): Boolean {
    val c = toInt().toChar()
    return c in 'a'..'z' || c in 'A'..'Z' || c in '0'..'9' || c == '#'
}

private const val BUFFER_SIZE = 8 * 1024
private const val BYTE_MASK = 0xFF

/** Longer than any entity name or numeric reference; past it, an `&` is bare. */
private const val MAX_REFERENCE = 34
private const val HEX = 16
private const val DECIMAL = 10
private const val MAX_CODE_POINT = 0x10FFFF

/** Tab, line feed and carriage return: the only controls XML allows. */
private val ALLOWED_CONTROLS = setOf(0x9, 0xA, 0xD)
private val ALLOWED_RANGES = listOf(0x20..0xD7FF, 0xE000..0xFFFD, 0x10000..MAX_CODE_POINT)

private const val AMP = '&'.code.toByte()
private const val LT = '<'.code.toByte()
private const val GT = '>'.code.toByte()
private const val RSQB = ']'.code.toByte()
private const val DASH = '-'.code.toByte()
private const val HASH = '#'.code.toByte()
private const val SEMICOLON = ';'.code.toByte()
private const val LOWER_X = 'x'.code.toByte()
private const val UPPER_X = 'X'.code.toByte()

private val EMPTY = ByteArray(0)
private val ESCAPED_AMP = "&amp;".toByteArray(Charsets.US_ASCII)
private val CDATA_OPEN = "<![CDATA[".toByteArray(Charsets.US_ASCII)
private val COMMENT_OPEN = "<!--".toByteArray(Charsets.US_ASCII)

private val XML_ENTITIES = listOf("amp", "lt", "gt", "quot", "apos").map { it.toByteArray(Charsets.US_ASCII) }

/** HTML's Latin-1 entities, U+00A0 to U+00FF in order. */
private const val LATIN_1 = "nbsp iexcl cent pound curren yen brvbar sect uml copy ordf laquo not " +
    "shy reg macr deg plusmn sup2 sup3 acute micro para middot cedil sup1 ordm raquo frac14 frac12 " +
    "frac34 iquest Agrave Aacute Acirc Atilde Auml Aring AElig Ccedil Egrave Eacute Ecirc Euml Igrave " +
    "Iacute Icirc Iuml ETH Ntilde Ograve Oacute Ocirc Otilde Ouml times Oslash Ugrave Uacute Ucirc Uuml " +
    "Yacute THORN szlig agrave aacute acirc atilde auml aring aelig ccedil egrave eacute ecirc euml " +
    "igrave iacute icirc iuml eth ntilde ograve oacute ocirc otilde ouml divide oslash ugrave uacute " +
    "ucirc uuml yacute thorn yuml"
private const val LATIN_1_FIRST = 0xA0

private val HTML_ENTITIES: Map<String, Int> =
    LATIN_1.split(' ').withIndex().associate { (i, name) -> name to LATIN_1_FIRST + i } +
        mapOf(
            "OElig" to 0x152, "oelig" to 0x153, "Scaron" to 0x160, "scaron" to 0x161, "Yuml" to 0x178,
            "ndash" to 0x2013, "mdash" to 0x2014, "lsquo" to 0x2018, "rsquo" to 0x2019, "sbquo" to 0x201A,
            "ldquo" to 0x201C, "rdquo" to 0x201D, "bdquo" to 0x201E, "bull" to 0x2022, "hellip" to 0x2026,
            "prime" to 0x2032, "euro" to 0x20AC, "trade" to 0x2122,
        )

/** Each HTML entity's numeric reference, made once rather than for every one a guide carries. */
private val HTML_REFERENCES: Map<String, ByteArray> =
    HTML_ENTITIES.mapValues { (_, code) -> "&#$code;".toByteArray(Charsets.US_ASCII) }
