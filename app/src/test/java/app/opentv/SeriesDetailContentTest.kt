/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv

import androidx.activity.compose.BackHandler
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.isFocused
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.requestFocus
import androidx.test.espresso.Espresso
import app.opentv.data.model.Series
import app.opentv.data.repo.CoverStatus
import app.opentv.data.repo.EpisodeStatuses
import app.opentv.data.repo.MediaKey
import app.opentv.data.repo.SeriesMainAction
import app.opentv.data.repo.WatchStatus
import app.opentv.ui.options.LocalOpenOptions
import app.opentv.ui.options.OptionsFor
import app.opentv.ui.options.OptionsPanel
import app.opentv.ui.options.OptionsSubject
import app.opentv.ui.vod.NEXT_SEASON_PEEK
import app.opentv.ui.vod.SeriesDetailContent
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
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
@OptIn(ExperimentalTestApi::class)
class SeriesDetailContentTest {

    @get:Rule
    val compose = createComposeRule()

    @get:Rule
    val artwork = PlaceholderArtwork()

    private var series = Series(
        id = 1,
        sourceId = 1,
        seriesId = "s",
        name = "The Long Way Round",
        categoryId = null,
        posterUrl = "https://example.com/poster.jpg",
        rating = null,
        year = 2024,
        plot = "Two friends and a lot of road.",
        backdropUrl = "https://example.com/backdrop.jpg",
    )

    private var episodes = listOf(1, 2).flatMap { season -> (1..5).map { testEpisode(season, it) } }

    private var played: MediaKey? = null

    private var openedPerson: String? = null

    private var leaves = 0

    private var heroStatus: CoverStatus? = null

    private var episodeStatuses = EpisodeStatuses()

    private val favourite = mutableStateOf<Boolean?>(false)

    private val favouriteRequests = mutableListOf<Boolean>()

    private val mainAction = mutableStateOf<SeriesMainAction?>(null)

    private fun show(initial: SeriesMainAction?) {
        mainAction.value = initial
        compose.setTvContent {
            // Stands in for the NavHost: whatever Back the page doesn't take leaves it.
            BackHandler { leaves++ }
            SeriesDetailContent(
                series = series,
                episodes = episodes,
                statuses = episodeStatuses,
                mainAction = mainAction.value,
                status = heroStatus,
                favourite = favourite.value,
                onPlayEpisode = { played = it },
                onSetFavourite = { favouriteRequests += it },
                onOpenPerson = { openedPerson = it },
            )
        }
        settle()
    }

    // The page places its highlight a moment after it appears, once its rows are laid out.
    private fun settle() {
        compose.waitForIdle()
        compose.mainClock.advanceTimeBy(500)
    }

    @Test
    fun `with an Up Next the main button plays it and starts highlighted`() {
        show(SeriesMainAction.Play(testEpisode(2, 5)))

        compose.onNodeWithText("Play S2 E5").assertIsFocused()
        captureScreen("series_detail_up_next")
    }

    @Test
    fun `with an episode In Progress the main button resumes it`() {
        show(SeriesMainAction.Resume(testEpisode(2, 4)))

        compose.onNodeWithText("Resume S2 E4").assertIsFocused()
        captureScreen("series_detail_resume")
    }

    @Test
    fun `with nothing to pick up the main button plays the first episode`() {
        show(SeriesMainAction.Play(testEpisode(1, 1)))

        compose.onNodeWithText("Play S1 E1").assertIsFocused()
        captureScreen("series_detail_first_episode")
    }

    @Test
    fun `a show with an episode In Progress in Season 2 opens on Season 2, and one Down reaches that episode`() {
        show(SeriesMainAction.Resume(testEpisode(2, 4)))

        compose.onNodeWithText("Season 2").assertIsSelected()
        compose.onNodeWithText("Season 1").assertIsNotSelected()
        val resume = compose.onNodeWithText("Resume S2 E4")
        resume.assertIsFocused()

        resume.performKeyInput { pressKey(Key.DirectionDown) }
        settle()

        compose.onNode(hasText("S2 E4") and hasClickAction()).assertIsFocused()
    }

    /** Presses [key] on whatever has the Highlight, and lets the page settle. */
    private fun press(key: Key) {
        compose.onNode(isFocused()).performKeyInput { pressKey(key) }
        settle()
    }

    /** [press] in a side panel, a window of its own with its own highlight beside the page's. */
    private fun pressInPanel(key: Key) {
        compose.onNode(isFocused() and hasAnyAncestor(isDialog())).performKeyInput { pressKey(key) }
        settle()
    }

    private fun episodeRow(season: Int, episode: Int) = compose.onNode(
        hasText("S$season E$episode") and hasClickAction(),
    )

    private fun pill(label: String) = compose.onNode(hasText(label) and hasClickAction())

    private fun SemanticsNodeInteraction.assertNotShown() {
        val shown = runCatching { assertIsDisplayed() }.isSuccess
        assertWithMessage("still on screen").that(shown).isFalse()
    }

    @Test
    fun `the list opens already at the main button's episode, so Down moves onto it without a jump`() {
        episodes = (1..10).map { testEpisode(1, it) } + (1..3).map { testEpisode(2, it) }
        show(SeriesMainAction.Resume(testEpisode(1, 7)))

        episodeRow(1, 7).assertIsDisplayed()
        episodeRow(1, 1).assertNotShown()

        press(Key.DirectionDown)

        episodeRow(1, 7).assertIsFocused()
        episodeRow(1, 1).assertNotShown()
    }

    @Test
    fun `under a season's last episode the next season's first shows faded, to say Down goes on`() {
        show(SeriesMainAction.Resume(testEpisode(1, 4)))
        press(Key.DirectionDown)

        press(Key.DirectionDown)

        episodeRow(1, 5).assertIsFocused()
        compose.onNode(hasTestTag(NEXT_SEASON_PEEK)).assertIsDisplayed()
        captureScreen("series_detail_next_season_peek")

        press(Key.DirectionDown)

        episodeRow(2, 1).assertIsFocused()
        pill("Season 2").assertIsSelected()
    }

    @Test
    fun `walking down a long season, the faded next episode comes into view under its last`() {
        episodes = (1..12).map { testEpisode(1, it) } + (1..3).map { testEpisode(2, it) }
        show(SeriesMainAction.Play(testEpisode(1, 1)))
        press(Key.DirectionDown)

        repeat(11) { press(Key.DirectionDown) }

        episodeRow(1, 12).assertIsFocused()
        // Wholly in view, not a sliver at the foot of the list: the list clips what's outside it.
        val peek = compose.onNode(hasTestTag(NEXT_SEASON_PEEK)).fetchSemanticsNode()
        assertWithMessage("peek clipped to ${peek.boundsInRoot}, though ${peek.size} tall")
            .that(peek.boundsInRoot.height).isEqualTo(peek.size.height.toFloat())
    }

    @Test
    fun `the last season's last episode has nothing faded under it`() {
        show(SeriesMainAction.Resume(testEpisode(2, 5)))

        press(Key.DirectionDown)

        episodeRow(2, 5).assertIsFocused()
        compose.onNode(hasTestTag(NEXT_SEASON_PEEK)).assertDoesNotExist()
    }

    @Test
    fun `Up from a season's first episode highlights its pill`() {
        show(SeriesMainAction.Resume(testEpisode(2, 1)))
        press(Key.DirectionDown)
        episodeRow(2, 1).assertIsFocused()

        press(Key.DirectionUp)

        pill("Season 2").assertIsFocused()
        captureScreen("series_detail_season_pill")
    }

    @Test
    fun `Left and Right along the pills change the season and its episodes with no OK`() {
        show(SeriesMainAction.Resume(testEpisode(2, 1)))
        press(Key.DirectionDown)
        press(Key.DirectionUp)

        press(Key.DirectionLeft)

        pill("Season 1").assertIsFocused().assertIsSelected()
        episodeRow(1, 1).assertExists()
        episodeRow(2, 1).assertDoesNotExist()

        press(Key.DirectionRight)

        pill("Season 2").assertIsFocused().assertIsSelected()
        episodeRow(2, 1).assertExists()
        episodeRow(1, 1).assertDoesNotExist()
    }

    @Test
    fun `Down from a season's last episode highlights the next season's first and selects its pill`() {
        show(SeriesMainAction.Resume(testEpisode(1, 5)))
        press(Key.DirectionDown)
        episodeRow(1, 5).assertIsFocused()

        press(Key.DirectionDown)

        episodeRow(2, 1).assertIsFocused()
        pill("Season 2").assertIsSelected().assertIsDisplayed()
    }

    @Test
    fun `Down from the last episode of the last season stays put`() {
        show(SeriesMainAction.Resume(testEpisode(2, 5)))
        press(Key.DirectionDown)

        press(Key.DirectionDown)

        episodeRow(2, 5).assertIsFocused()
        pill("Season 2").assertIsSelected()
    }

    /** Fails unless [label]'s pill is wholly on screen, not cut off at an edge. */
    private fun assertPillWhollyInView(label: String) {
        val screen = compose.onRoot().fetchSemanticsNode().boundsInRoot
        val pill = pill(label).fetchSemanticsNode().boundsInRoot
        assertWithMessage(
            "$label at $pill on $screen",
        ).that(pill.left >= screen.left && pill.right <= screen.right).isTrue()
    }

    @Test
    fun `with 15 seasons the highlighted pill stays in view along the row and as Down crosses seasons`() {
        episodes = (1..15).flatMap { season -> (1..2).map { testEpisode(season, it) } }
        show(SeriesMainAction.Resume(testEpisode(1, 1)))
        press(Key.DirectionDown)

        repeat(14) { crossed ->
            press(Key.DirectionDown)
            press(Key.DirectionDown)
            episodeRow(crossed + 2, 1).assertIsFocused()
            assertPillWhollyInView("Season ${crossed + 2}")
        }
        captureScreen("series_detail_many_seasons")

        press(Key.DirectionUp)
        pill("Season 15").assertIsFocused()
        for (season in 14 downTo 1) {
            press(Key.DirectionLeft)
            pill("Season $season").assertIsFocused()
            assertPillWhollyInView("Season $season")
        }
    }

    @Test
    fun `Back from deep in the episodes highlights the selected pill, and a second Back leaves`() {
        show(SeriesMainAction.Resume(testEpisode(2, 1)))
        repeat(4) { press(Key.DirectionDown) }
        episodeRow(2, 4).assertIsFocused()

        Espresso.pressBack()
        settle()

        pill("Season 2").assertIsFocused()
        assertThat(leaves).isEqualTo(0)

        Espresso.pressBack()
        settle()

        assertThat(leaves).isEqualTo(1)
    }

    @Test
    fun `going into the episodes moves the hero away, and Up past the pills brings it back`() {
        show(SeriesMainAction.Resume(testEpisode(2, 1)))

        press(Key.DirectionDown)

        compose.onNodeWithText("Two friends and a lot of road.").assertDoesNotExist()
        compose.onNodeWithText("Favourite").assertDoesNotExist()
        compose.onNodeWithText("The Long Way Round").assertIsDisplayed()
        pill("Season 2").assertIsDisplayed()
        captureScreen("series_detail_episode_list")

        press(Key.DirectionUp)
        pill("Season 2").assertIsFocused()
        compose.onNodeWithText("Favourite").assertDoesNotExist()

        press(Key.DirectionUp)

        compose.onNodeWithText("Resume S2 E1").assertIsFocused()
        compose.onNodeWithText("Two friends and a lot of road.").assertIsDisplayed()
    }

    @Test
    fun `a one-season show has no pills, and Up from its first episode reaches the buttons`() {
        episodes = (1..3).map { testEpisode(1, it) }
        show(SeriesMainAction.Play(testEpisode(1, 1)))
        compose.onNodeWithText("Season 1").assertDoesNotExist()

        press(Key.DirectionDown)
        episodeRow(1, 1).assertIsFocused()
        press(Key.DirectionUp)

        compose.onNodeWithText("Play S1 E1").assertIsFocused()
        compose.onNodeWithText("Two friends and a lot of road.").assertIsDisplayed()
    }

    @Test
    fun `Season 0 is labelled Specials and comes after the last season`() {
        episodes = listOf(testEpisode(0, 1)) + episodes
        show(SeriesMainAction.Play(testEpisode(2, 5)))

        val specials = pill("Specials").fetchSemanticsNode().boundsInRoot
        val last = pill("Season 2").fetchSemanticsNode().boundsInRoot
        assertThat(specials.left).isGreaterThan(last.right)
        compose.onNodeWithText("Season 0").assertDoesNotExist()
    }

    @Test
    fun `the meta line gives the genre, how many seasons and the year`() {
        series = series.copy(genre = "Drama, Crime")
        show(SeriesMainAction.Play(testEpisode(1, 1)))

        compose.onNodeWithText("Drama · 2 seasons · 2024").assertIsDisplayed()
    }

    @Test
    fun `a one-season show's meta line says 1 season, and leaves out the specials`() {
        series = series.copy(genre = "Drama")
        episodes = listOf(testEpisode(0, 1)) + (1..3).map { testEpisode(1, it) }
        show(SeriesMainAction.Play(testEpisode(1, 1)))

        compose.onNodeWithText("Drama · 1 season · 2024").assertIsDisplayed()
    }

    @Test
    fun `a show In Progress says where you are on the meta line`() {
        series = series.copy(genre = "Drama")
        heroStatus =
            CoverStatus.InProgress(progress = 0.5f, remainingMillis = 18 * 60_000L, episode = testEpisode(2, 4))
        show(SeriesMainAction.Resume(testEpisode(2, 4)))

        compose.onNodeWithText("Drama · 2 seasons · 2024 · S2 E4 · 18 min left").assertIsDisplayed()
    }

    @Test
    fun `a Watched show says so on the meta line`() {
        series = series.copy(genre = "Drama")
        heroStatus = CoverStatus.Watched
        show(SeriesMainAction.Play(testEpisode(1, 1)))

        compose.onNodeWithText("Drama · 2 seasons · 2024 · Watched").assertIsDisplayed()
    }

    @Test
    fun `while episodes load the list says so`() {
        episodes = emptyList()
        show(initial = null)

        compose.onNodeWithText("Loading episodes…").assertIsDisplayed()
    }

    @Test
    fun `each episode row says where you are with it`() {
        episodeStatuses = EpisodeStatuses(
            mapOf(
                testEpisode(1, 1).id to WatchStatus.Watched,
                testEpisode(1, 2).id to WatchStatus.InProgress(progress = 0.5f, remainingMillis = 18 * 60_000L),
            ),
        )
        // No main button, so the list starts at the top rather than at the episode to resume.
        show(initial = null)

        compose.onNode(hasText("S1 E1") and hasText("Watched")).assertExists()
        compose.onNode(hasText("S1 E2") and hasText("18 min left")).assertExists()
        compose.onNode(hasText("S1 E3") and hasText("Watched")).assertDoesNotExist()
    }

    @Test
    fun `episodes arriving before the viewer has moved select the main button's season`() {
        show(initial = null)
        pill("Season 1").assertIsSelected()

        mainAction.value = SeriesMainAction.Resume(testEpisode(2, 4))
        settle()

        pill("Season 2").assertIsSelected()
        compose.onNodeWithText("Resume S2 E4").assertIsFocused()
    }

    @Test
    fun `pressing the main button plays its episode`() {
        show(SeriesMainAction.Resume(testEpisode(2, 4)))

        compose.onNodeWithText("Resume S2 E4").performClick()

        assertThat(played).isEqualTo(MediaKey.Episode(24))
    }

    @Test
    fun `before the episodes arrive the highlight waits on the favourite button`() {
        show(initial = null)

        compose.onNodeWithText("Favourite").assertIsFocused()
        compose.onNode(hasText("Play", substring = true)).assertDoesNotExist()
    }

    @Test
    fun `episodes arriving after the viewer has moved on leave the highlight where it is`() {
        show(initial = null)
        compose.onNode(hasText("S1 E2") and hasClickAction()).requestFocus()

        mainAction.value = SeriesMainAction.Play(testEpisode(1, 1))
        settle()

        compose.onNode(hasText("S1 E2") and hasClickAction()).assertIsFocused()
    }

    @Test
    fun `episodes arriving while the highlight still waits on the favourite button move it to the main button`() {
        show(initial = null)

        mainAction.value = SeriesMainAction.Play(testEpisode(1, 1))
        settle()

        compose.onNodeWithText("Play S1 E1").assertIsFocused()
    }

    @Test
    fun `Cast comes after Favourite and lists who is in the show, and OK on a name opens their page`() {
        series = series.copy(cast = "Lead Actor, Second Lead")
        show(SeriesMainAction.Play(testEpisode(2, 5)))

        press(Key.DirectionRight)
        press(Key.DirectionRight)
        compose.onNodeWithText("Cast").assertIsFocused()
        press(Key.DirectionCenter)

        compose.onNode(hasText("Lead Actor") and hasAnyAncestor(isDialog())).assertIsFocused()
        captureScreen("series_detail_cast")
        pressInPanel(Key.DirectionDown)
        pressInPanel(Key.DirectionCenter)

        assertThat(openedPerson).isEqualTo("Second Lead")
    }

    @Test
    fun `a show with no cast has no Cast button`() {
        show(SeriesMainAction.Play(testEpisode(2, 5)))

        compose.onNodeWithText("Cast").assertDoesNotExist()
    }

    @Test
    fun `a Favourite series offers to remove it`() {
        favourite.value = true
        show(SeriesMainAction.Play(testEpisode(2, 5)))

        compose.onNodeWithText("Remove favourite").performClick()

        assertThat(favouriteRequests).containsExactly(false)
        captureScreen("series_detail_favourite")
    }

    @Test
    fun `a series that is not a Favourite offers to add it`() {
        show(SeriesMainAction.Play(testEpisode(2, 5)))

        compose.onNodeWithText("Favourite").performClick()

        assertThat(favouriteRequests).containsExactly(true)
    }

    private val optionsAskedFor = mutableStateListOf<OptionsSubject>()

    /** The page with its options panel stood in for, and the highlight on season [season]'s pill. */
    private fun showOnSeasonPill(season: Int): SemanticsNodeInteraction {
        compose.setTvContent {
            CompositionLocalProvider(LocalOpenOptions provides { subject, _ -> optionsAskedFor += subject }) {
                SeriesDetailContent(
                    series = series,
                    episodes = episodes,
                    statuses = EpisodeStatuses(),
                    mainAction = SeriesMainAction.Play(testEpisode(1, 1)),
                    status = null,
                    favourite = false,
                    onPlayEpisode = { played = it },
                    onSetFavourite = {},
                    onOpenPerson = {},
                )
            }
            optionsAskedFor.lastOrNull()?.let { subject ->
                val options = OptionsFor.Season(listOf(WatchStatus.Unwatched)).options()
                OptionsPanel(subject.title, options, onChoose = {}, onDismiss = {})
            }
        }
        settle()
        return compose.onNode(hasText("Season $season") and hasClickAction()).also { it.requestFocus() }
    }

    private val seasonTwoOptions = OptionsSubject.OfSeason(
        episodes = episodes.filter { it.season == 2 },
        title = "Season 2",
    )

    @Test
    fun `Select on a season pill opens its options`() {
        showOnSeasonPill(season = 2).performKeyInput { pressKey(Key.DirectionCenter) }

        assertThat(optionsAskedFor).containsExactly(seasonTwoOptions)
        compose.onNodeWithText("Mark season watched").assertExists()
        captureScreen("series_detail_season_options")
    }

    @Test
    fun `holding Select on a season pill opens its options once`() {
        showOnSeasonPill(season = 2).performKeyInput {
            keyDown(Key.DirectionCenter)
            advanceEventTime(1_000)
            keyUp(Key.DirectionCenter)
        }

        assertThat(optionsAskedFor).containsExactly(seasonTwoOptions)
    }

    @Test
    fun `Menu on a season pill opens its options`() {
        showOnSeasonPill(season = 2).performKeyInput { pressKey(Key.Menu) }

        assertThat(optionsAskedFor).containsExactly(seasonTwoOptions)
    }

    @Test
    fun `the favourite button does nothing until the Profile's Favourites have loaded`() {
        favourite.value = null
        show(SeriesMainAction.Play(testEpisode(2, 5)))

        compose.onNodeWithText("Favourite").performClick()

        assertThat(favouriteRequests).isEmpty()
    }
}
