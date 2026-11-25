/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv

import app.opentv.data.model.Category
import app.opentv.data.model.Source
import app.opentv.data.model.SourceKind
import app.opentv.data.model.StreamKind
import app.opentv.data.repo.CatalogRepository
import app.opentv.data.repo.CategoryLockCarry
import app.opentv.sync.SyncBundle
import app.opentv.sync.SyncCategoryLock
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * A Parental Lock locks the same category on every device, whatever row id the provider has on
 * each, and stays on after an update changes how category names are tidied (#338).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class SyncCategoryLocksTest {

    private val deviceA = SyncDevice(rowIdOffset = 0)
    private val deviceB = SyncDevice(rowIdOffset = 100)

    @get:Rule(order = 0)
    val graphA = deviceA.testGraph

    @get:Rule(order = 1)
    val graphB = deviceB.testGraph

    private fun testing(block: suspend CoroutineScope.() -> Unit) = runBlocking(block = block)

    private suspend fun SyncDevice.seedWithCategories() {
        seed()
        addLiveCategory(NAMELESS, "-----")
        addLiveCategory(ADULT, "UK| Adult")
        addLiveCategory(NEWS, "UK| News")
    }

    private fun SyncDevice.lockCarry() = CategoryLockCarry(db, graph.settings)

    @Test
    fun `a locked category with no name stays hidden on a device where the provider has another row id`() = testing {
        deviceA.seedWithCategories()
        deviceB.seedWithCategories()
        deviceA.lockCategory(NAMELESS, at = 1_000)

        deviceB.syncFrom(deviceA)

        assertThat(deviceA.shownCategoryIds()).containsExactly(ADULT, NEWS)
        assertThat(deviceB.shownCategoryIds()).containsExactly(ADULT, NEWS)
    }

    @Test
    fun `a locked category with no name stays locked when its provider is renamed, here and on the other device`() =
        testing {
            deviceA.now = 1_000
            deviceA.seedWithCategories()
            deviceB.seedWithCategories()
            deviceA.lockCategory(NAMELESS, at = 1_000)
            deviceB.syncFrom(deviceA)

            deviceA.now = 2_000
            deviceA.renameProvider("Sky")
            deviceB.syncFrom(deviceA)

            assertThat(deviceA.shownCategoryIds()).containsExactly(ADULT, NEWS)
            assertThat(deviceB.providerName()).isEqualTo("Sky")
            assertThat(deviceB.shownCategoryIds()).containsExactly(ADULT, NEWS)
        }

    @Test
    fun `a locked category with no name comes back locked when its provider is removed and added again`() = testing {
        deviceA.seedWithCategories()
        deviceA.lockCategory(NAMELESS, at = 1_000)

        deviceA.graph.catalogRepository.deleteSource(deviceA.sourceId)
        // Added again, it gets a new row id.
        val readded = deviceA.db.sources().insert(
            Source(name = "Provider", kind = SourceKind.XTREAM, url = SyncDevice.PANEL_URL),
        )
        deviceA.db.categories().upsertAll(
            listOf(NAMELESS to "-----", ADULT to "UK| Adult", NEWS to "UK| News").map { (id, name) ->
                Category(id = id, sourceId = readded, name = name, kind = StreamKind.LIVE)
            },
        )

        assertThat(deviceA.shownCategoryIds()).containsExactly(ADULT, NEWS)
    }

    @Test
    fun `a lock keyed by a normaliser that has since changed is moved to the key this one gives`() = testing {
        deviceB.seedWithCategories()
        // As a device on an older normaliser sends it: a key this version no longer gives "UK| Adult".
        deviceB.sync.apply(
            SyncBundle(categoryLocks = listOf(SyncCategoryLock("ukadultold", 1_000, names = listOf("UK| Adult")))),
        )
        assertThat(deviceB.shownCategoryIds()).contains(ADULT)

        deviceB.lockCarry().carry(from = CatalogRepository.NORMALIZER_VERSION - 1)

        assertThat(deviceB.shownCategoryIds()).containsExactly(NAMELESS, NEWS)
    }

    @Test
    fun `a lock made before locks kept their names learns them on launch, so the next normaliser change can move it`() =
        testing {
            deviceA.seedWithCategories()
            deviceA.graph.settings.setHiddenCategories(setOf("adult"))

            deviceA.lockCarry().remember()

            assertThat(deviceA.graph.settings.exportCategoryLocks().single { it.key == "adult" }.names)
                .containsExactly("UK| Adult")
        }

    @Test
    fun `a lock whose category isn't listed yet learns its name on the first launch after the provider lists it`() =
        testing {
            deviceA.seed()
            deviceA.graph.settings.setHiddenCategories(setOf("adult"))
            deviceA.lockCarry().remember()

            deviceA.addLiveCategory(ADULT, "UK| Adult")
            deviceA.lockCarry().remember()

            assertThat(deviceA.graph.settings.exportCategoryLocks().single { it.key == "adult" }.names)
                .containsExactly("UK| Adult")
        }

    @Test
    fun `a nameless category locked under this device's old row id key is locked on every device after launch`() =
        testing {
            deviceA.seedWithCategories()
            deviceB.seedWithCategories()
            deviceA.graph.settings.setHiddenCategories(setOf("${deviceA.sourceId}:$NAMELESS"))

            deviceA.lockCarry().remember()
            deviceB.syncFrom(deviceA)

            assertThat(deviceA.shownCategoryIds()).containsExactly(ADULT, NEWS)
            assertThat(deviceB.shownCategoryIds()).containsExactly(ADULT, NEWS)
        }

    private companion object {
        const val NAMELESS = "66"
        const val ADULT = "67"
        const val NEWS = "68"
    }
}
