/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv

import app.opentv.data.repo.inBatches
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import org.junit.Test

/**
 * The loop behind the background film and series lookups: it works through every title due a lookup,
 * a batch at a time, reading no more rows each batch than it uses.
 */
class TitleLookupsTest {

    /**
     * Titles due a lookup, newest first, as the due queries order them. A title leaves once its lookup
     * succeeds; one in [failing] stays due, unless [filledElsewhere] fills it in after the batch it's keyed
     * by (counting from 1), as opening a title does. Records every limit asked for and every batch looked up.
     */
    private class DueTitles(
        ids: List<Long>,
        private val failing: Set<Long> = emptySet(),
        private val answering: Boolean = true,
        private val filledElsewhere: Map<Int, Long> = emptyMap(),
    ) {
        val due = ids.toMutableList()
        val limits = mutableListOf<Int>()
        val batches = mutableListOf<List<Long>>()

        fun select(limit: Int): List<Long> {
            limits += limit
            return due.take(limit)
        }

        fun lookUp(titles: List<Long>): Boolean {
            batches += titles
            due.removeAll { it in titles && it !in failing }
            filledElsewhere[batches.size]?.let { due.remove(it) }
            return answering
        }
    }

    @Test
    fun `when every lookup succeeds each batch reads only as many titles as it looks up`() = runTest {
        val titles = DueTitles((1L..7L).toList())

        inBatches(count = 2, pauseMillis = 0, id = { it }, select = titles::select, lookUp = titles::lookUp)

        assertThat(titles.limits).containsExactly(2, 2, 2, 2).inOrder()
        assertThat(titles.batches).containsExactly(listOf(1L, 2L), listOf(3L, 4L), listOf(5L, 6L), listOf(7L)).inOrder()
    }

    @Test
    fun `a failed lookup widens later batches by one and is never looked up twice`() = runTest {
        val titles = DueTitles((1L..6L).toList(), failing = setOf(2L, 4L))

        inBatches(count = 2, pauseMillis = 0, id = { it }, select = titles::select, lookUp = titles::lookUp)

        // Each failure is seen still due by the select after its batch, which then makes room for it.
        assertThat(titles.limits).containsExactly(2, 2, 3, 3, 4).inOrder()
        assertThat(titles.batches).containsExactly(listOf(1L, 2L), listOf(3L), listOf(4L, 5L), listOf(6L)).inOrder()
        assertThat(titles.batches.flatten()).containsNoDuplicates()
    }

    @Test
    fun `a batch stays at its size when a failed title is filled in elsewhere`() = runTest {
        val titles = DueTitles((1L..7L).toList(), failing = setOf(1L), filledElsewhere = mapOf(2 to 1L))

        inBatches(count = 2, pauseMillis = 0, id = { it }, select = titles::select, lookUp = titles::lookUp)

        assertThat(titles.limits).containsExactly(2, 2, 3, 2, 2).inOrder()
        assertThat(titles.batches).containsExactly(listOf(1L, 2L), listOf(3L), listOf(4L, 5L), listOf(6L, 7L)).inOrder()
    }

    @Test
    fun `nothing more is looked up once the provider stops answering`() = runTest {
        val titles = DueTitles((1L..6L).toList(), answering = false)

        inBatches(count = 2, pauseMillis = 0, id = { it }, select = titles::select, lookUp = titles::lookUp)

        assertThat(titles.limits).containsExactly(2)
        assertThat(titles.batches).containsExactly(listOf(1L, 2L))
    }

    @Test
    fun `nothing more is selected once fewer titles are due than a batch holds`() = runTest {
        val titles = DueTitles((1L..3L).toList())

        inBatches(count = 2, pauseMillis = 0, id = { it }, select = titles::select, lookUp = titles::lookUp)

        assertThat(titles.limits).containsExactly(2, 2).inOrder()
        assertThat(titles.batches).containsExactly(listOf(1L, 2L), listOf(3L)).inOrder()
    }

    @Test
    fun `nothing is looked up when no title is due`() = runTest {
        val titles = DueTitles(emptyList())

        inBatches(count = 2, pauseMillis = 0, id = { it }, select = titles::select, lookUp = titles::lookUp)

        assertThat(titles.limits).containsExactly(2)
        assertThat(titles.batches).isEmpty()
    }

    @Test
    fun `batches rest between each other but not after the last`() = runTest {
        val titles = DueTitles((1L..5L).toList())

        inBatches(count = 2, pauseMillis = 60_000, id = { it }, select = titles::select, lookUp = titles::lookUp)

        assertThat(titles.batches).hasSize(3)
        assertThat(testScheduler.currentTime).isEqualTo(120_000)
    }
}
