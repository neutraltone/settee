/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv

import app.opentv.data.repo.TitleCover
import app.opentv.ui.vod.CoverPrefetcher
import coil.request.Disposable
import coil.request.ImageResult
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CompletableDeferred
import org.junit.Test

/**
 * Which covers the Movies and Shows grids fetch ahead of the viewer, and which of those they give up
 * on once the viewer has gone past them (#264). Four covers a row, two rows ahead.
 */
class CoverPrefetcherTest {

    private val covers = (0L until 80L).map { TitleCover(it, "$it", "Title $it", "https://example.com/$it.jpg") }

    /** Every prefetch asked for, in order. */
    private val prefetches = mutableListOf<Prefetch>()

    private val prefetcher = CoverPrefetcher(covers, rowsAhead = 2) { url -> Prefetch(url).also { prefetches += it } }

    private fun urls(range: IntRange) = range.map { "https://example.com/$it.jpg" }

    private fun asked(range: IntRange) = prefetches.filter { it.url in urls(range) }

    @Test
    fun `moving down gives up on the covers it has gone past and keeps the ones still ahead`() {
        prefetcher.onShown(first = 0, last = 7, columns = 4)
        assertThat(prefetches.map { it.url }).containsExactlyElementsIn(urls(8..15)).inOrder()

        // Held Down: the next layout is three rows further on.
        prefetcher.onShown(first = 12, last = 19, columns = 4)

        assertThat(asked(8..11).map { it.cancelled }).containsExactly(true, true, true, true)
        assertThat(asked(12..15).map { it.cancelled }).containsExactly(false, false, false, false)
        assertThat(prefetches.drop(8).map { it.url }).containsExactlyElementsIn(urls(20..27)).inOrder()
    }

    @Test
    fun `turning back up gives up on the covers below and fetches the ones above`() {
        prefetcher.onShown(first = 40, last = 47, columns = 4)
        assertThat(prefetches.map { it.url }).containsExactlyElementsIn(urls(48..55)).inOrder()

        prefetcher.onShown(first = 36, last = 43, columns = 4)

        assertThat(asked(48..55).map { it.cancelled }).containsExactly(true, true, true, true, true, true, true, true)
        assertThat(prefetches.drop(8).map { it.url }).containsExactlyElementsIn(urls(28..35)).inOrder()
    }

    @Test
    fun `a layout that hasn't moved a row keeps the direction the viewer was going`() {
        prefetcher.onShown(first = 40, last = 47, columns = 4)
        prefetcher.onShown(first = 36, last = 43, columns = 4)

        // Mid-scroll frames: the same covers on screen, a few pixels further up.
        prefetcher.onShown(first = 36, last = 43, columns = 4)
        prefetcher.onShown(first = 36, last = 43, columns = 4)

        assertThat(asked(28..35).map { it.cancelled }).doesNotContain(true)
        assertThat(prefetches.drop(16).map { it.url }).isEmpty()
    }

    @Test
    fun `a cover given up on is asked for again when it comes back into the window`() {
        prefetcher.onShown(first = 0, last = 7, columns = 4)
        prefetcher.onShown(first = 24, last = 31, columns = 4)
        assertThat(asked(8..15).map { it.cancelled }).doesNotContain(false)

        prefetcher.onShown(first = 16, last = 23, columns = 4)

        assertThat(asked(8..15).filterNot { it.cancelled }.map { it.url }).containsExactlyElementsIn(urls(8..15))
    }

    @Test
    fun `a cover already fetched is not asked for again`() {
        prefetcher.onShown(first = 0, last = 7, columns = 4)
        prefetches.forEach { it.finished = true }

        // A row at a time down, past them, and back up over them.
        prefetcher.onShown(first = 4, last = 11, columns = 4)
        prefetcher.onShown(first = 8, last = 15, columns = 4)
        prefetcher.onShown(first = 20, last = 27, columns = 4)
        prefetcher.onShown(first = 12, last = 19, columns = 4)
        prefetcher.onShown(first = 4, last = 11, columns = 4)

        assertThat(prefetches.map { it.url }).containsNoDuplicates()
    }

    @Test
    fun `cancelling all gives up on every prefetch that hasn't finished, and leaves the finished ones`() {
        prefetcher.onShown(first = 0, last = 7, columns = 4)
        asked(8..11).forEach { it.finished = true }

        prefetcher.cancelAll()

        assertThat(asked(8..11).map { it.cancelled }).containsExactly(false, false, false, false)
        assertThat(asked(12..15).map { it.cancelled }).containsExactly(true, true, true, true)
    }

    /** A prefetch the test finishes or the prefetcher cancels, like Coil's own. */
    private class Prefetch(val url: String) : Disposable {
        override val job = CompletableDeferred<ImageResult>()
        var cancelled = false
            private set
        var finished = false

        override val isDisposed get() = cancelled || finished

        override fun dispose() {
            check(!finished) { "$url was cancelled after it finished" }
            cancelled = true
        }
    }
}
