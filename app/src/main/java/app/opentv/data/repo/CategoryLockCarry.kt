/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv.data.repo

import app.opentv.core.AppSettings
import app.opentv.data.db.OpenTvDatabase
import app.opentv.data.model.Category
import app.opentv.data.model.StreamKind
import app.opentv.data.parser.ChannelNameNormalizer
import app.opentv.sync.SyncCategoryLock

/** The last `NORMALIZER_VERSION` whose keys held only ASCII letters and digits (#345). */
internal const val LAST_ASCII_KEY_NORMALIZER = 3

/**
 * Keeps Parental Lock's locks on the categories they were set on when the key a category is locked
 * by changes. Keys are worked out from names on every read, so without this a new normaliser that
 * gives a category another key shows it, with no PIN, the moment the update opens (#338).
 *
 * The old normaliser is gone by then, so the old key can't be worked out again: instead each lock
 * keeps the names of the categories it was set on, and the new normaliser keys those names afresh.
 */
internal class CategoryLockCarry(private val db: OpenTvDatabase, private val settings: AppSettings) {

    /**
     * On a launch with the normaliser unchanged: gives the locks that have no names yet (made before
     * locks kept them, or sent by an older device) the names of the categories they lock today, so
     * the next normaliser change can move them. A nameless category locked under this device's row
     * id, as before #338, is locked under its provider's name as well. Reads the catalogue only when
     * a lock lacks names, and not again for the same locks until the catalogue or the providers
     * change: a lock for a provider this device doesn't have would otherwise have every launch read
     * every category.
     */
    suspend fun remember() {
        val locks = settings.exportCategoryLocks()
        val nameless = locks.filter { it.names.isEmpty() }
        if (nameless.isEmpty()) return
        val providers = db.sources().all()
        val searched = listOf(
            nameless.map { it.key }.sorted().joinToString("\n"),
            db.categories().count().toString(),
            providers.sortedBy { it.id }.joinToString("\n") { "${it.id}:${it.name}" },
        ).joinToString("\u0000")
        if (settings.lockNamesSearched == searched) return
        val remembered = rememberedCategoryLocks(categories(), SourceDirectory(providers), nameless)
        settings.importCategoryLocks(remembered)
        val named = remembered.map { it.key }.toSet()
        settings.lockNamesSearched = searched.takeIf { nameless.any { it.key !in named } }
    }

    /** After the normaliser moved on from version [from]: locks each lock's categories under their new keys. */
    suspend fun carry(from: Int) {
        val locks = settings.exportCategoryLocks()
        if (locks.isEmpty()) return
        settings.importCategoryLocks(carriedCategoryLocks(categories(), sources(), locks, from))
    }

    private suspend fun categories() = StreamKind.entries.flatMap { db.categories().allByKind(it) }

    private suspend fun sources() = SourceDirectory(db.sources().all())
}

/**
 * Parental Lock's locks as they travel between devices. A nameless category's lock is keyed by its
 * provider's name, which this relabels with the name the provider has here, following the renames
 * [SyncArrival] knows, before the locks are taken (ADR 0005).
 */
class SyncedCategoryLocks internal constructor(private val db: OpenTvDatabase, private val settings: AppSettings) {
    /** Takes another device's [locks], each dated on this device's clock. */
    suspend fun import(locks: List<SyncCategoryLock>, arrival: SyncArrival = SyncArrival.NONE) {
        val sources = arrival.sources(db.sources())
        val names = mutableMapOf<String, String>()
        val here = locks.map { lock ->
            lock.copy(key = NamelessCategory.relabelled(lock.key) { names.getOrPut(it) { sources.currentName(it) } })
        }
        settings.importCategoryLocks(here, arrival.clock)
    }
}

/** [locks] without names, with the names of the [categories] each locks today. */
internal fun rememberedCategoryLocks(
    categories: List<Category>,
    sources: SourceDirectory,
    locks: List<SyncCategoryLock>,
): List<SyncCategoryLock> = locks.filter { it.names.isEmpty() }.flatMap { lock ->
    val locked = categories.filter {
        lock.key == categoryGroupKey(it, sources) || lock.key == NamelessCategory.legacyKey(it.sourceId, it.id)
    }
    val names = locked.map { it.name }.distinct()
    if (names.isEmpty()) return@flatMap emptyList()
    val keys = listOf(lock.key) + locked.map { categoryGroupKey(it, sources) }
    keys.distinct().map { SyncCategoryLock(it, lock.lockedAtMillis, names) }
}

/**
 * The locks [categories] need under today's [categoryGroupKey] to stay as locked as [locks] left
 * them, saved by normaliser version [from]. Each keeps the time its old lock was made, and the old
 * keys stay too: a device still on the old version sends them.
 *
 * A lock's names are keyed afresh, and so is every category of those names here, which also catches
 * one whose name now normalises to nothing. A nameless category's lock follows that one category.
 * A lock with no names, from before locks kept them, can only be carried from the ASCII-only keys of
 * version 3 and earlier, which can still be worked out (#345).
 *
 * An old key that covered several categories, as `ru` covered every Cyrillic one of a region, locks
 * each of them: that was the lock in force, and over-locking is the safer mistake.
 */
internal fun carriedCategoryLocks(
    categories: List<Category>,
    sources: SourceDirectory,
    locks: List<SyncCategoryLock>,
    from: Int,
): List<SyncCategoryLock> {
    val carried = LinkedHashMap<String, SyncCategoryLock>()
    for (lock in locks) {
        val (keys, names) = keysNow(lock, categories, sources, from)
        for (key in keys - lock.key) {
            val there = carried[key]
            carried[key] = SyncCategoryLock(
                key,
                maxOf(there?.lockedAtMillis ?: lock.lockedAtMillis, lock.lockedAtMillis),
                (there?.names.orEmpty() + names).distinct(),
            )
        }
    }
    return carried.values.toList()
}

/** The keys today's normaliser gives the categories a lock was set on, and those categories' names. */
private data class LockedNow(val keys: Set<String>, val names: List<String>)

private fun keysNow(
    lock: SyncCategoryLock,
    categories: List<Category>,
    sources: SourceDirectory,
    from: Int,
): LockedNow {
    val nameless = NamelessCategory.parse(lock.key)
    val locked = when {
        nameless != null -> categories.filter {
            sources.name(it.sourceId) == nameless.sourceName && it.id == nameless.categoryId
        }
        lock.names.isNotEmpty() -> categories.filter { it.name in lock.names }
        from <= LAST_ASCII_KEY_NORMALIZER -> categories.filter {
            val old = ChannelNameNormalizer.asciiOnlyGroupKey(it.name)
            old.ifEmpty { NamelessCategory.legacyKey(it.sourceId, it.id) } == lock.key
        }
        else -> emptyList()
    }
    // Names this device has no category of are keyed too: its provider may list them again.
    val fromNames = when (nameless) {
        null -> lock.names.map { ChannelNameNormalizer.normalize(it).groupKey }.filter { it.isNotEmpty() }
        else -> emptyList()
    }
    val keys = locked.mapTo(LinkedHashSet()) { categoryGroupKey(it, sources) } + fromNames
    return LockedNow(keys, lock.names.ifEmpty { locked.map(Category::name).distinct() })
}
