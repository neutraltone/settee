/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.unit.dp
import app.opentv.pairing.PairingServer
import app.opentv.ui.onboarding.Listening
import app.opentv.ui.onboarding.PairingConnecting
import app.opentv.ui.onboarding.PairingStarting
import app.opentv.ui.onboarding.PhonePairingScreen
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

// Plain Application: OpenTvApp schedules WorkManager on start, which a lone composable doesn't need.
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = TV_1080P, application = android.app.Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class PhonePairingScreenTest {

    @get:Rule
    val compose = createComposeRule()

    // A fixed session rather than PairingServer's, whose address and tokens change every run.
    private val session = PairingServer.Session(
        url = "http://192.168.0.14:41234/?t=0123456789abcdef",
        confirmCode = "482913",
        host = "192.168.0.14",
        port = 41234,
        shortToken = "k7mx4ht",
    )

    @Test
    fun `a phone that can't scan the QR code is shown an address it can type`() {
        compose.setTvContent {
            // Laid out as PhonePairingScreen lays it out.
            Box(Modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
                Listening(session, onCancel = {})
            }
        }

        compose.onNodeWithText("192.168.0.14:41234/k7mx4ht").assertIsDisplayed()
        captureScreen("phone_pairing_listening")
    }

    @Test
    fun `while pairing gets ready, the aerial TV waits`() {
        compose.setTvContent { PairingStarting() }

        compose.onNodeWithContentDescription("Getting pairing ready").assertIsDisplayed()
        captureScreen("phone_pairing_starting")
    }

    @Test
    fun `once the phone has sent the details, the aerial TV waits over what's happening`() {
        compose.setTvContent { PairingConnecting() }

        compose.onNodeWithContentDescription("Still working").assertIsDisplayed()
        compose.onNodeWithText("Got it. Connecting to your provider…").assertIsDisplayed()
        captureScreen("phone_pairing_connecting")
    }

    @Test
    fun `once a provider hands on to the TMDB step, it stays on Got it rather than showing its code again`() {
        // The server listens again for the key as the channels come in; handed on, that isn't shown.
        compose.setTvContent { PhonePairingScreen(onReceived = {}, onCancel = {}, keepListening = { true }) }

        compose.onNodeWithText("Got it. Connecting to your provider…").assertIsDisplayed()
        compose.onNode(hasText("Getting pairing ready")).assertDoesNotExist()
        compose.onNode(hasText(session.shortUrl)).assertDoesNotExist()
    }
}
