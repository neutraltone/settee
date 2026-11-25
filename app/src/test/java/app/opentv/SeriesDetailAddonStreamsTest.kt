/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv

import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isFocused
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.requestFocus
import app.opentv.core.ViewerError
import app.opentv.data.model.Episode
import app.opentv.data.model.StreamLink
import app.opentv.data.model.StremioStream
import app.opentv.data.repo.EpisodeStatuses
import app.opentv.data.repo.RankedStream
import app.opentv.data.repo.SeriesMainAction
import app.opentv.ui.design.ERROR_DETAIL_TAG
import app.opentv.ui.options.ItemOptionsHost
import app.opentv.ui.vod.AddonSearch
import app.opentv.ui.vod.SeriesDetailContent
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flowOf
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** An episode's Add-on sources, from its options on the show's page, with the app's real options panel. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = TV_1080P, application = android.app.Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@OptIn(ExperimentalTestApi::class)
class SeriesDetailAddonStreamsTest {

    private val compose = createComposeRule()

    @get:Rule
    val testGraph = TestGraph(compose)

    @get:Rule
    val artwork = PlaceholderArtwork()

    private val episodes = (1..5).map { testEpisode(1, it) }

    private fun ranked(url: String, title: String, addon: String, label: String) =
        RankedStream(StremioStream(StreamLink.Direct(url), title, addon), 0, label, null, playable = true)

    private val streams = listOf(
        ranked("https://example.com/s1e3-fhd.mkv", "Episode 3 1080p", "First add-on", "FHD"),
        ranked("https://example.com/s1e3-hd.mkv", "Episode 3 720p", "Second add-on", "HD"),
    )

    private var findStreams: (Episode) -> Flow<AddonSearch> = { flowOf(AddonSearch.Results(streams, finished = true)) }
    private val askedFor = mutableListOf<Episode>()
    private var played: Pair<Episode, StremioStream>? = null

    private fun show(addons: Boolean = true) {
        compose.setTvContent {
            ItemOptionsHost {
                SeriesDetailContent(
                    series = testSeries(),
                    episodes = episodes,
                    statuses = EpisodeStatuses(),
                    mainAction = SeriesMainAction.Play(episodes.first()),
                    status = null,
                    favourite = false,
                    onPlayEpisode = {},
                    onSetFavourite = {},
                    onOpenPerson = {},
                    findAddonStreams = if (addons) {
                        { episode ->
                            askedFor += episode
                            findStreams(episode)
                        }
                    } else {
                        null
                    },
                    onPlayAddonStream = { episode, stream -> played = episode to stream },
                )
            }
        }
        compose.settleShowPage()
    }

    /** Opens episode 3's options with Menu, from its row. */
    private fun openEpisodeOptions() {
        compose.episodeRow(1, 3).requestFocus().performKeyInput { pressKey(Key.Menu) }
        compose.settleShowPage()
    }

    /**
     * Presses [key] on the highlighted [text]. Not on whatever is focused: the page's row keeps focus in
     * its own window under the options panel and the picker.
     */
    private fun press(text: String, key: Key) {
        compose.onNode(hasText(text) and hasClickAction() and isFocused()).performKeyInput { pressKey(key) }
        compose.settleShowPage()
    }

    /** Opens episode 3's Add-on sources from its options, by remote. */
    private fun askAddonsForEpisodeThree() {
        openEpisodeOptions()
        compose.onNode(hasText("Add-on sources") and hasClickAction()).requestFocus()
        press("Add-on sources", Key.DirectionCenter)
    }

    @Test
    fun `without add-ons an episode's options don't offer Add-on sources`() {
        show(addons = false)

        openEpisodeOptions()

        compose.onNodeWithText("Mark watched").assertIsDisplayed()
        compose.onNodeWithText("Add-on sources").assertDoesNotExist()
    }

    @Test
    fun `an episode's options open a picker of its add-on streams that plays the one picked by remote`() {
        show()

        openEpisodeOptions()
        compose.onNodeWithText("Add-on sources").assertIsDisplayed()
        compose.onNode(hasText("Add-on sources") and hasClickAction()).requestFocus()
        press("Add-on sources", Key.DirectionCenter)

        assertThat(askedFor).containsExactly(episodes[2])
        compose.onNodeWithText("Choose a stream").assertIsDisplayed()
        compose.onNode(hasText("Episode 3 1080p") and isFocused()).assertExists()
        compose.onNodeWithText("First add-on · FHD").assertIsDisplayed()
        captureScreen("series_detail_addon_picker")

        press("Episode 3 1080p", Key.DirectionDown)
        compose.onNode(hasText("Episode 3 720p") and hasClickAction()).assertIsFocused()
        press("Episode 3 720p", Key.DirectionCenter)

        assertThat(played).isEqualTo(episodes[2] to streams[1].stream)
        compose.onNodeWithText("Choose a stream").assertDoesNotExist()
    }

    @Test
    fun `while no add-on has answered about the episode, the picker says it's looking`() {
        findStreams = { emptyFlow() }
        show()

        askAddonsForEpisodeThree()

        compose.onNodeWithText("Finding streams from your add-ons…").assertIsDisplayed()
        captureScreen("series_detail_addon_picker_loading")
    }

    @Test
    fun `the episode's streams show as the first add-ons answer, with a line while the rest are asked`() {
        val answers = MutableStateFlow<AddonSearch>(AddonSearch.Results(streams.take(1), finished = false))
        findStreams = { answers }
        show()

        askAddonsForEpisodeThree()

        compose.onNode(hasText("Episode 3 1080p") and isFocused()).assertExists()
        compose.onNodeWithText("Still asking your other add-ons…").assertIsDisplayed()
        captureScreen("series_detail_addon_picker_partial")

        answers.value = AddonSearch.Results(streams, finished = true)
        compose.settleShowPage()

        compose.onNodeWithText("Episode 3 720p").assertIsDisplayed()
        compose.onNodeWithText("Still asking your other add-ons…").assertDoesNotExist()
    }

    @Test
    fun `when no add-on has the episode, the picker says none were found`() {
        findStreams = { flowOf(AddonSearch.Results(emptyList(), finished = true)) }
        show()

        askAddonsForEpisodeThree()

        compose.onNodeWithText("No streams found from your add-ons for this title.").assertIsDisplayed()
        captureScreen("series_detail_addon_picker_none")
    }

    @Test
    fun `a show with no IMDb match says the add-ons can't look it up, with nothing to try again`() {
        val sentence = "Your add-ons can't look this show up, as it has no match on IMDb."
        findStreams = { flowOf(AddonSearch.CantAsk(ViewerError(sentence, "NoImdbMatch"), canRetry = false)) }
        show()

        askAddonsForEpisodeThree()

        compose.onNodeWithText(sentence).assertIsDisplayed()
        compose.onNode(hasTestTag(ERROR_DETAIL_TAG) and hasText("NoImdbMatch"), useUnmergedTree = true).assertExists()
        compose.onNodeWithText("Try again").assertDoesNotExist()
        captureScreen("series_detail_addon_picker_no_match")
    }
}
