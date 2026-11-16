/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv

import app.opentv.data.model.Category
import app.opentv.data.model.StreamKind
import app.opentv.data.repo.carriedCategoryLocks
import app.opentv.data.repo.categoryGroupKey
import app.opentv.sync.SyncCategoryLock
import com.google.common.truth.Truth.assertThat
import org.junit.Test

/** A category locked before #345 changed its key stays locked under the new one. */
class CategoryLockCarryTest {

    private fun category(id: String, name: String) =
        Category(id = id, sourceId = 1, name = name, kind = StreamKind.LIVE)

    @Test
    fun `a Cyrillic category locked under its old key is locked under its new key, from the same time`() {
        val adult = category("7", "RU| Для взрослых")

        // Before #345 the key dropped every Cyrillic letter, leaving the region: `ru`.
        val carried = carriedCategoryLocks(listOf(adult), listOf(SyncCategoryLock("ru", lockedAtMillis = 5)))

        assertThat(carried).containsExactly(SyncCategoryLock(categoryGroupKey(adult), lockedAtMillis = 5))
        assertThat(categoryGroupKey(adult)).isNotEqualTo("ru")
    }

    @Test
    fun `every category the old key locked stays locked, as the safer mistake for a lock`() {
        val adult = category("7", "RU| Для взрослых")
        val news = category("8", "RU| Новости")

        val carried = carriedCategoryLocks(listOf(adult, news), listOf(SyncCategoryLock("ru", lockedAtMillis = 5)))

        assertThat(carried.map { it.key }).containsExactly(categoryGroupKey(adult), categoryGroupKey(news))
    }

    @Test
    fun `categories whose key didn't change, or that weren't locked, carry nothing`() {
        val carried = carriedCategoryLocks(
            listOf(category("1", "UK| Adult"), category("2", "RU| Спорт")),
            listOf(SyncCategoryLock("adult", lockedAtMillis = 5)),
        )

        assertThat(carried).isEmpty()
    }
}
