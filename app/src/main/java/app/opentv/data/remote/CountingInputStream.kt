/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv.data.remote

import java.io.FilterInputStream
import java.io.InputStream

/** Tells [counted] how many bytes have been read through it so far, for a download's progress. */
internal class CountingInputStream(stream: InputStream, private val counted: (Long) -> Unit) :
    FilterInputStream(stream) {
    private var total = 0L

    override fun read(): Int = super.read().also { if (it >= 0) counted(++total) }

    override fun read(b: ByteArray, off: Int, len: Int): Int = super.read(b, off, len).also {
        if (it > 0) {
            total += it
            counted(total)
        }
    }
}
