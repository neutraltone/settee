/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv

import androidx.test.core.app.ApplicationProvider
import app.opentv.core.ViewerError
import app.opentv.data.repo.CatalogueRefresh
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** A Catalogue Refresh that fails says why in Voice, with a detail line and nothing from the exception (#166). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class CatalogueRefreshFailureTest {

    private val test = TestCatalog(ApplicationProvider.getApplicationContext())
    private val db = test.db
    private val panel = test.panel
    private val refresh = test.refresh()

    init {
        panel.replies[""] = """{"user_info": {"status": "Active"}}"""
        panel.replies["get_live_streams"] = """[{"stream_id": 1, "name": "BBC One"}]"""
    }

    @After
    fun close() {
        test.settings.forgetDatabaseRows()
        test.close()
    }

    @Test
    fun `adding a provider that can't be reached says so, and nothing from the exception's message`() = runTest {
        db.sources().insert(PANEL_SOURCE)
        panel.replies[""] = "!host"

        val outcome = refresh.refresh(CatalogueRefresh.Trigger.ProviderAdded(PANEL_SOURCE))

        val reason = (outcome as CatalogueRefresh.Outcome.AddFailed).reason
        assertThat(reason).isEqualTo(
            ViewerError(
                "Can't reach your provider. Check this device is online and the address is right.",
                "UnknownHost",
            ),
        )
        listOf(reason.sentence, reason.detail).forEach { text ->
            assertThat(text).doesNotContain("example.com")
            assertThat(text).doesNotContain("password=")
            assertThat(text).doesNotContain("00:1A:79")
        }
    }

    @Test
    fun `adding a provider that rejects the login says to check the login`() = runTest {
        db.sources().insert(PANEL_SOURCE)
        panel.replies[""] = """{"user_info": {"auth": 0}}"""

        val outcome = refresh.refresh(CatalogueRefresh.Trigger.ProviderAdded(PANEL_SOURCE))

        assertThat((outcome as CatalogueRefresh.Outcome.AddFailed).reason).isEqualTo(
            ViewerError(
                "Your provider didn't accept your login. Check your provider's details in Settings.",
                "LoginRejected",
            ),
        )
    }

    @Test
    fun `adding a provider that answers with an HTTP error puts the status on the detail line`() = runTest {
        db.sources().insert(PANEL_SOURCE)
        panel.replies[""] = "!503"

        val outcome = refresh.refresh(CatalogueRefresh.Trigger.ProviderAdded(PANEL_SOURCE))

        assertThat((outcome as CatalogueRefresh.Outcome.AddFailed).reason).isEqualTo(
            ViewerError("Your provider is having problems. That's at their end, so try again later.", "HTTP 503"),
        )
    }

    @Test
    fun `a refresh whose provider can't be reached keeps the reason, for the guide to show`() = runTest {
        db.sources().insert(PANEL_SOURCE)
        panel.replies[""] = "!host"

        val outcome = refresh.refresh(CatalogueRefresh.Trigger.Manual)

        assertThat(outcome.failure).isEqualTo(
            ViewerError(
                "Can't reach your provider. Check this device is online and the address is right.",
                "UnknownHost",
            ),
        )
        assertThat(refresh.state.value.last).isEqualTo(outcome)
    }

    @Test
    fun `a refresh that worked has no failure to show`() = runTest {
        db.sources().insert(PANEL_SOURCE)

        assertThat(refresh.refresh(CatalogueRefresh.Trigger.Manual).failure).isNull()
    }

    private val unreachable = ViewerError(
        "Can't reach your provider. Check this device is online and the address is right.",
        "UnknownHost",
    )

    @Test
    fun `movies that fail during setup are kept as that provider's failure, apart from its shows`() = runTest {
        db.sources().insert(PANEL_SOURCE)
        panel.replies["get_vod_streams"] = "!host"
        panel.replies["get_series"] = """[{"series_id": 7, "name": "Taskmaster"}]"""

        refresh.refresh(CatalogueRefresh.Trigger.Setup(PANEL_SOURCE))
        test.awaitRefresh()

        val failures = refresh.state.value.vodFailures
        assertThat(failures.movies).containsExactly(PANEL_SOURCE.id, unreachable)
        assertThat(failures.shows).isEmpty()
    }

    @Test
    fun `shows that fail in a background refresh are kept, until a later refresh downloads them`() = runTest {
        db.sources().insert(PANEL_SOURCE)
        panel.replies["get_series"] = "!host"

        refresh.refresh(CatalogueRefresh.Trigger.Periodic)

        assertThat(refresh.state.value.vodFailures.shows).containsExactly(PANEL_SOURCE.id, unreachable)

        panel.replies["get_series"] = """[{"series_id": 7, "name": "Taskmaster"}]"""
        refresh.refresh(CatalogueRefresh.Trigger.Periodic)

        assertThat(refresh.state.value.vodFailures.shows).isEmpty()
    }
}
