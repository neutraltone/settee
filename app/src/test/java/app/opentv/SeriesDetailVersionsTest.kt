/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import app.opentv.data.repo.EpisodeStatuses
import app.opentv.data.repo.MediaKey
import app.opentv.data.repo.SeriesMainAction
import app.opentv.data.repo.VersionTraits
import app.opentv.data.repo.VodQualities
import app.opentv.ui.vod.PageVersions
import app.opentv.ui.vod.SeriesDetailContent
import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

// Plain Application: OpenTvApp schedules WorkManager on start, which a lone composable doesn't need.

/** A show's page and its Versions: the Quality pill, another language, and nothing playing here (#181, #433). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = TV_1080P, application = android.app.Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class SeriesDetailVersionsTest {

    @get:Rule
    val compose = createComposeRule()

    @get:Rule
    val artwork = PlaceholderArtwork()

    private var episodes = listOf(1, 2).flatMap { season -> (1..5).map { testEpisode(season, it) } }

    private val mainAction = mutableStateOf<SeriesMainAction?>(null)

    // What the page shows of the show's Versions; set before [show], or after to change them on the page.
    private var versions by mutableStateOf(PageVersions())

    private var played: MediaKey? = null

    private val chosen = mutableListOf<MediaKey>()

    private fun show(initial: SeriesMainAction?) {
        mainAction.value = initial
        compose.setTvContent {
            SeriesDetailContent(
                series = testSeries(),
                episodes = episodes,
                statuses = EpisodeStatuses(),
                mainAction = mainAction.value,
                status = null,
                favourite = false,
                onPlayEpisode = { played = it },
                onSetFavourite = {},
                onOpenPerson = {},
                versions = versions,
                onChooseVersion = { chosen += it },
            )
        }
        settle()
    }

    private fun settle() = compose.settleShowPage()

    private fun press(key: Key) = compose.pressOnShowPage(key)

    private fun episodeRow(season: Int, episode: Int) = compose.episodeRow(season, episode)

    private fun pill(label: String) = compose.showPagePill(label)

    @Test
    fun `a show's Quality pill chooses between its Versions for the whole show`() {
        versions = PageVersions(
            choices = listOf(
                VodQualities.Choice(
                    MediaKey.Episode(11),
                    VersionTraits(qualityRank = 400, qualityLabel = "4K"),
                    false,
                    false,
                ),
                VodQualities.Choice(
                    MediaKey.Episode(31),
                    VersionTraits(qualityRank = 200, qualityLabel = "HD"),
                    playable = true,
                    playing = false,
                ),
                VodQualities.Choice(MediaKey.Episode(51), VersionTraits(), playable = true, playing = true),
            ),
        )
        show(SeriesMainAction.Play(testEpisode(1, 1)))

        compose.onNodeWithText("Quality").performClick()
        settle()

        compose.onNodeWithText("4K").assertIsNotEnabled()
        compose.onNodeWithText("HD").assertIsEnabled()
        compose.onNodeWithText("Other").assertIsSelected()
        captureScreen("series_detail_quality_pill")
    }

    @Test
    fun `a show's Play asks before another language`() {
        versions = PageVersions(otherLanguage = PageVersions.OtherLanguage(MediaKey.Episode(51), "it"))
        show(SeriesMainAction.Play(testEpisode(1, 1)))

        compose.onNodeWithText("Play S1 E1", substring = true).performClick()
        settle()
        compose.onNodeWithText("Play in Italian").performClick()
        settle()

        assertThat(chosen).containsExactly(MediaKey.Episode(51))
        assertThat(played).isEqualTo(MediaKey.Episode(testEpisode(1, 1).id))
    }

    @Test
    fun `an episode doesn't start when nothing of the show plays here`() {
        versions = PageVersions(cantPlay = "4K")
        show(SeriesMainAction.Play(testEpisode(1, 1)))

        compose.onNodeWithText("can't play 4K", substring = true).assertIsDisplayed()
        compose.onNodeWithText("Episode 2", substring = true).performClick()
        settle()

        assertThat(played).isNull()
    }

    @Test
    fun `a show this TV can't play opens on Favourite`() {
        versions = PageVersions(cantPlay = "4K")
        show(SeriesMainAction.Play(testEpisode(1, 1)))

        compose.onNodeWithText("Favourite").assertIsFocused()
        captureScreen("series_detail_cant_play")
    }

    @Test
    fun `on a show this TV can't play, Up past the pills gets back to Favourite`() {
        versions = PageVersions(cantPlay = "4K")
        show(SeriesMainAction.Play(testEpisode(1, 1)))

        press(Key.DirectionDown)
        episodeRow(1, 1).assertIsFocused()
        press(Key.DirectionUp)
        pill("Season 1").assertIsFocused()
        press(Key.DirectionUp)

        compose.onNodeWithText("Favourite").assertIsFocused()
    }

    @Test
    fun `on a one-season show this TV can't play, Up from the first episode gets back to Favourite`() {
        versions = PageVersions(cantPlay = "4K")
        episodes = (1..3).map { testEpisode(1, it) }
        show(SeriesMainAction.Play(testEpisode(1, 1)))

        press(Key.DirectionDown)
        episodeRow(1, 1).assertIsFocused()
        press(Key.DirectionUp)

        compose.onNodeWithText("Favourite").assertIsFocused()
    }

    @Test
    fun `on a show this TV can't play, episodes arriving late leave the highlight on Favourite`() {
        versions = PageVersions(cantPlay = "4K")
        show(initial = null)

        mainAction.value = SeriesMainAction.Play(testEpisode(1, 1))
        settle()

        compose.onNodeWithText("Favourite").assertIsFocused()
    }

    @Test
    fun `a show that stops playing here while its main button is highlighted moves the highlight to Favourite`() {
        show(SeriesMainAction.Play(testEpisode(1, 1)))
        compose.onNodeWithText("Play S1 E1").assertIsFocused()

        versions = PageVersions(cantPlay = "4K")
        settle()

        compose.onNodeWithText("Favourite").assertIsFocused()
    }
}
