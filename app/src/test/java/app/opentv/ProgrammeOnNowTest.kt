/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.createComposeRule
import app.opentv.ui.player.rememberProgrammeOnNow
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CompletableDeferred
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class ProgrammeOnNowTest {

    @get:Rule
    val compose = createComposeRule()

    private val answers = mutableMapOf<String, CompletableDeferred<String?>>()
    private var lookups = 0
    private var channel by mutableStateOf(ONE)
    private var onScreen by mutableStateOf(true)
    private var shown: String? = null

    private fun answer(channel: String, title: String?) {
        answers.getValue(channel).complete(title)
        compose.waitForIdle()
    }

    private fun show() {
        compose.setContent {
            if (onScreen) {
                val asked = channel
                shown = rememberProgrammeOnNow(asked) {
                    lookups++
                    answers.getOrPut(asked) { CompletableDeferred() }.await()
                }
            }
        }
        compose.waitForIdle()
    }

    @Test
    fun `gives the programme the lookup found`() {
        show()

        answer(ONE, "News")

        assertThat(shown).isEqualTo("News")
    }

    @Test
    fun `a slow lookup for an earlier channel doesn't overwrite the latest`() {
        show()
        val slow = answers.getValue(ONE)

        channel = TWO
        compose.waitForIdle()
        answer(TWO, "Film")
        slow.complete("News")
        compose.waitForIdle()

        assertThat(shown).isEqualTo("Film")
    }

    @Test
    fun `the last channel's programme is gone as soon as the channel changes`() {
        show()
        answer(ONE, "News")

        channel = TWO
        compose.waitForIdle()

        assertThat(shown).isNull()
    }

    @Test
    fun `looks up once for as long as it's on screen, and again when it comes back`() {
        show()
        answer(ONE, "News")

        compose.mainClock.advanceTimeBy(LONG_WHILE_MILLIS)
        assertThat(lookups).isEqualTo(1)

        onScreen = false
        compose.waitForIdle()
        answers[ONE] = CompletableDeferred()
        onScreen = true
        compose.waitForIdle()
        answer(ONE, "Weather")

        assertThat(lookups).isEqualTo(2)
        assertThat(shown).isEqualTo("Weather")
    }

    @Test
    fun `a failed lookup leaves no programme rather than taking the player down`() {
        answers[ONE] = CompletableDeferred<String?>().apply { completeExceptionally(IllegalStateException("database")) }

        show()

        assertThat(shown).isNull()
    }

    private companion object {
        const val LONG_WHILE_MILLIS = 300_000L
        const val ONE = "one"
        const val TWO = "two"
    }
}
