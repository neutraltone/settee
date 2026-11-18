/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv

import androidx.test.core.app.ApplicationProvider
import app.opentv.core.ViewerError
import app.opentv.data.model.refreshFailure
import app.opentv.data.repo.CatalogueRefresh
import app.opentv.ui.isTheViewersToFix
import app.opentv.ui.providerFailures
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.flow.first
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
    fun `adding an address that answers with a web page says to check the address, not to try again`() = runTest {
        db.sources().insert(PANEL_SOURCE)
        panel.replies[""] = "<!DOCTYPE html><html><body>Welcome to our website</body></html>"

        val outcome = refresh.refresh(CatalogueRefresh.Trigger.ProviderAdded(PANEL_SOURCE))

        assertThat((outcome as CatalogueRefresh.Outcome.AddFailed).reason).isEqualTo(
            ViewerError(notAPanel, "NotAPanel"),
        )
    }

    @Test
    fun `adding a provider whose channel list is cut off still says to try again`() = runTest {
        db.sources().insert(PANEL_SOURCE)
        panel.replies["get_live_streams"] = """[{"stream_id": 1, "name": "BBC One"}, {"stream_id": 2, "na"""

        val outcome = refresh.refresh(CatalogueRefresh.Trigger.ProviderAdded(PANEL_SOURCE))

        assertThat((outcome as CatalogueRefresh.Outcome.AddFailed).reason).isEqualTo(
            ViewerError("Couldn't load your provider's channels. Try again in a moment.", "BadReply"),
        )
    }

    @Test
    fun `a broken login reply that starts with a byte-order mark still says to try again`() = runTest {
        db.sources().insert(PANEL_SOURCE)
        panel.replies[""] = "\uFEFF{\"user_info\": {\"status\": \"Act"

        val outcome = refresh.refresh(CatalogueRefresh.Trigger.ProviderAdded(PANEL_SOURCE))

        assertThat((outcome as CatalogueRefresh.Outcome.AddFailed).reason).isEqualTo(
            ViewerError("Couldn't load your provider's channels. Try again in a moment.", "BadReply"),
        )
    }

    @Test
    fun `adding an address with nothing where an Xtream login should be says to check the address`() = runTest {
        db.sources().insert(PANEL_SOURCE)
        panel.replies[""] = "!404"

        val outcome = refresh.refresh(CatalogueRefresh.Trigger.ProviderAdded(PANEL_SOURCE))

        assertThat((outcome as CatalogueRefresh.Outcome.AddFailed).reason).isEqualTo(
            ViewerError(notAPanel, "NotAPanel · HTTP 404"),
        )
    }

    @Test
    fun `a refresh keeps each provider's reason by its id, and counts them apart from the guide's feeds`() = runTest {
        db.sources().insert(PANEL_SOURCE)
        db.sources().insert(PANEL_SOURCE.copy(id = 2, name = "Second", username = "u2"))
        panel.replies["u/"] = "!host"
        panel.replies["xmltv.php"] =
            """<tv><programme start="20260101000000 +0000" stop="20260101010000 +0000" channel="bbc1">""" +
            "<title>News</title></programme></tv>"

        val outcome = refresh.refresh(CatalogueRefresh.Trigger.Manual)

        assertThat(outcome.failures).containsExactly(
            1L,
            ViewerError(
                "Can't reach your provider. Check this device is online and the address is right.",
                "UnknownHost",
            ),
        )
        assertThat(outcome.providerProblems).isEqualTo(1)
        assertThat(outcome.feedProblems).isEqualTo(0)
        assertThat(refresh.state.value.last).isEqualTo(outcome)
    }

    @Test
    fun `a provider's guide turning down the login raises no banner on Home`() = runTest {
        db.sources().insert(PANEL_SOURCE)
        panel.replies["xmltv.php"] = "!401"

        refresh.refresh(CatalogueRefresh.Trigger.Manual)

        val guide = test.guide.observeFeeds().first().single { it.providerSourceId == PANEL_SOURCE.id }
        assertThat(ViewerError.fromStored(guide.lastResult)?.detail).contains("HTTP 401")
        assertThat(db.sources().all().providerFailures().filter { it.error.isTheViewersToFix() }).isEmpty()
    }

    @Test
    fun `a refresh that worked has no failure to show`() = runTest {
        db.sources().insert(PANEL_SOURCE)

        assertThat(refresh.refresh(CatalogueRefresh.Trigger.Manual).failures).isEmpty()
    }

    @Test
    fun `a refresh saves a rejected login on that provider only, and a later refresh that works clears it`() = runTest {
        val second = PANEL_SOURCE.copy(id = 2, name = "Second", username = "u2")
        db.sources().insert(PANEL_SOURCE)
        db.sources().insert(second)
        panel.replies["u2/"] = """{"user_info": {"auth": 0}}"""

        refresh.refresh(CatalogueRefresh.Trigger.Manual)

        assertThat(db.sources().byId(2)?.refreshFailure).isEqualTo(
            ViewerError(
                "Your provider didn't accept your login. Check your provider's details in Settings.",
                "LoginRejected",
            ),
        )
        assertThat(db.sources().byId(1)?.refreshFailure).isNull()

        panel.replies.remove("u2/")
        refresh.refresh(CatalogueRefresh.Trigger.Periodic)

        assertThat(db.sources().byId(2)?.refreshFailure).isNull()
    }

    @Test
    fun `movies and shows that fail to download save no failure on the provider`() = runTest {
        db.sources().insert(PANEL_SOURCE)
        panel.replies["get_vod_streams"] = "!503"
        panel.replies["get_series"] = "!503"

        refresh.refresh(CatalogueRefresh.Trigger.Manual)

        assertThat(db.sources().byId(1)?.refreshFailure).isNull()
    }

    private val notAPanel =
        "That address didn't answer like an IPTV provider. " +
            "Check the address and port in your provider's details, or sign in to your Wi-Fi if it asks."

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
