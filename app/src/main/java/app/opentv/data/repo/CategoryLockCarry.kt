/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv.data.repo

import app.opentv.data.model.Category
import app.opentv.data.parser.ChannelNameNormalizer
import app.opentv.sync.SyncCategoryLock

/** The last `NORMALIZER_VERSION` whose keys held only ASCII letters and digits: what [carriedCategoryLocks] reads. */
internal const val LAST_ASCII_KEY_NORMALIZER = 3

/**
 * The locks [categories] need under today's [categoryGroupKey] to stay as locked as [locks], saved
 * under the ASCII-only keys of `NORMALIZER_VERSION` 3 and earlier, left them. Each keeps the time its
 * old lock was made. Keys are worked out from names on every read, so without this a category whose
 * key changed (`RU| Для взрослых` was `ru`) would show the moment the new version opened (#345).
 *
 * An old key that covered several categories, as `ru` covered every Cyrillic one of a region, locks
 * each of them: that was the lock in force, and over-locking is the safer mistake.
 */
internal fun carriedCategoryLocks(categories: List<Category>, locks: List<SyncCategoryLock>): List<SyncCategoryLock> {
    val lockedAt = locks.associate { it.key to it.lockedAtMillis }
    if (lockedAt.isEmpty()) return emptyList()
    val carried = LinkedHashMap<String, Long>()
    for (category in categories) {
        val key = categoryGroupKey(category)
        val old = ChannelNameNormalizer.asciiOnlyGroupKey(category.name)
            .ifEmpty { "${category.sourceId}:${category.id}" }
        val time = lockedAt[old]
        if (key !in lockedAt && time != null) carried[key] = maxOf(carried[key] ?: time, time)
    }
    return carried.map { (key, time) -> SyncCategoryLock(key, time) }
}
