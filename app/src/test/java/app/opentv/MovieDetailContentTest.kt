/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv

import android.app.Application
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isFocused
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.requestFocus
import androidx.test.espresso.Espresso
import app.opentv.core.ViewerError
import app.opentv.data.model.Movie
import app.opentv.data.model.StreamLink
import app.opentv.data.model.StremioStream
import app.opentv.data.repo.CoverStatus
import app.opentv.data.repo.MediaKey
import app.opentv.data.repo.RankedStream
import app.opentv.data.repo.VersionTraits
import app.opentv.data.repo.VodQualities
import app.opentv.ui.VodViewModel
import app.opentv.ui.design.ERROR_DETAIL_TAG
import app.opentv.ui.vod.AddonSearch
import app.opentv.ui.vod.MovieDetailContent
import app.opentv.ui.vod.MovieDetailScreen
import app.opentv.ui.vod.PageVersions
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
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
class MovieDetailContentTest {

    private val compose = createComposeRule()

    @get:Rule
    val testGraph = TestGraph(compose, http = FakePanel().http)

    @get:Rule
    val artwork = PlaceholderArtwork()

    private val film = Movie(
        id = 7,
        sourceId = 1,
        streamId = "m",
        name = "The Quiet Road",
        categoryId = null,
        posterUrl = "https://example.com/poster.jpg",
        rating = 7.8,
        year = 2021,
        plot = "A long drive home, and everything said on the way.",
        durationSeconds = 112 * 60,
        containerExtension = "mkv",
        streamUrl = "https://example.com/m.mkv",
        backdropUrl = "https://example.com/backdrop.jpg",
        genre = "Drama, Road",
        cast = "Somebody Famous, Second Billing, Third Billing, Fourth Billing",
        director = "Someone Else",
        writer = "A Writer, Second Writer",
        studio = "Big Studio",
    )

    private fun ranked(url: String, title: String, addon: String, label: String, size: Long? = null) =
        RankedStream(StremioStream(StreamLink.Direct(url), title, addon), 0, label, size, playable = true)

    private val streams = listOf(
        ranked("https://example.com/b.mkv", "The Quiet Road 2160p", "Second add-on", "4K", 9L shl 30),
        ranked("https://example.com/a.mkv", "The Quiet Road 1080p", "First add-on", "FHD"),
    )

    private var plays = 0

    // What the page shows of the film's Versions; set before [show].
    private var versions = PageVersions()
    private val chosen = mutableListOf<MediaKey>()
    private var playedStream: StremioStream? = null
    private val favouriteRequests = mutableListOf<Boolean>()
    private val favourite = mutableStateOf<Boolean?>(false)

    private var opened: Movie? = null
    private var openedPerson: String? = null

    // Null while the row is still being worked out.
    private val similarFilms = mutableStateOf<List<Movie>?>(emptyList())

    /** The film without credits, for tests of what's beneath them. */
    private val uncredited get() = film.copy(cast = null, director = null, writer = null, studio = null)

    private fun similarFilm(id: Long, name: String) = film.copy(id = id, streamId = "m$id", name = name)

    private var findStreams: () -> Flow<AddonSearch> = { flowOf(AddonSearch.Results(streams, finished = true)) }

    private fun show(
        movie: Movie = film,
        resumes: Boolean = false,
        status: CoverStatus? = null,
        addons: Boolean = false,
        similar: List<Movie>? = emptyList(),
    ) {
        similarFilms.value = similar
        compose.setTvContent {
            MovieDetailContent(
                movie = movie,
                resumes = resumes,
                status = status,
                favourite = favourite.value,
                findAddonStreams = if (addons) findStreams else null,
                similar = similarFilms.value,
                onOpenMovie = { opened = it },
                onOpenPerson = { openedPerson = it },
                onPlay = { plays++ },
                onPlayAddonStream = { playedStream = it },
                onSetFavourite = { favouriteRequests += it },
                versions = versions,
                onChooseVersion = { chosen += it },
            )
        }
        settle()
    }

    // The page places its highlight a moment after it appears.
    private fun settle() {
        compose.waitForIdle()
        compose.mainClock.advanceTimeBy(500)
    }

    private fun press(key: Key) {
        compose.onNode(isFocused()).performKeyInput { pressKey(key) }
        settle()
    }

    @Test
    fun `a film opens on Play, with its genre, year and runtime, and nothing beneath the hero`() {
        show()

        compose.onNodeWithText("Play").assertIsFocused()
        compose.onNodeWithText("Drama · 2021 · 1h 52m").assertIsDisplayed()
        compose.onNodeWithText("A long drive home, and everything said on the way.").assertIsDisplayed()
        compose.onNodeWithText("More like this").assertDoesNotExist()
        captureScreen("movie_detail")
    }

    @Test
    fun `a film named in Cyrillic draws its title in one face on an English interface`() {
        show(film.copy(name = "Бриллиантовая рука"))

        compose.onNodeWithText("Бриллиантовая рука").assertIsDisplayed()
        captureScreen("movie_detail_cyrillic_title")
    }

    @Test
    fun `the hero says who is in the film, its first three billed, and who directed it`() {
        show()

        compose.onNodeWithText("With Somebody Famous, Second Billing, Third Billing · Directed by Someone Else")
            .assertIsDisplayed()
    }

    @Test
    fun `a film with no credits has no credits line`() {
        compose.setTvContent {
            MovieDetailContent(
                movie = film.copy(cast = null, director = " ", writer = null, studio = null),
                resumes = false,
                status = null,
                favourite = false,
                findAddonStreams = null,
                similar = emptyList(),
                onOpenMovie = {},
                onOpenPerson = {},
                onPlay = {},
                onPlayAddonStream = {},
                onSetFavourite = {},
            )
        }
        settle()

        compose.onNode(hasText("Directed by", substring = true)).assertDoesNotExist()
        compose.onNode(hasText("With ", substring = true)).assertDoesNotExist()
        compose.onNodeWithText("Cast").assertDoesNotExist()
        compose.onNodeWithText("Director").assertDoesNotExist()
        compose.onNodeWithText("Writer").assertDoesNotExist()
    }

    @Test
    fun `the cast is a row of names beneath the buttons, and OK on one opens their page`() {
        show()

        compose.onNodeWithText("Cast").assertIsDisplayed()
        press(Key.DirectionDown)
        compose.onNode(hasText("Somebody Famous") and hasClickAction()).assertIsFocused()
        press(Key.DirectionRight)
        compose.onNode(hasText("Second Billing") and hasClickAction()).assertIsFocused()
        press(Key.DirectionCenter)

        assertThat(openedPerson).isEqualTo("Second Billing")
    }

    @Test
    fun `beneath the cast, who directed, wrote and made it are columns side by side`() {
        show()
        press(Key.DirectionDown)

        press(Key.DirectionDown)
        compose.onNode(hasText("Someone Else") and hasClickAction()).assertIsFocused()
        compose.onNodeWithText("Writer").assertIsDisplayed()
        compose.onNodeWithText("Studio").assertIsDisplayed()
        captureScreen("movie_detail_people")
        press(Key.DirectionRight)
        compose.onNode(hasText("A Writer") and hasClickAction()).assertIsFocused()
        press(Key.DirectionDown)
        compose.onNode(hasText("Second Writer") and hasClickAction()).assertIsFocused()
        press(Key.DirectionRight)
        compose.onNode(hasText("Big Studio") and hasClickAction()).assertIsFocused()
        press(Key.DirectionCenter)

        assertThat(openedPerson).isEqualTo("Big Studio")
    }

    @Test
    fun `a film with only a director has just that column`() {
        show(movie = film.copy(writer = null, studio = null))

        compose.onNodeWithText("Director").assertExists()
        compose.onNodeWithText("Writer").assertDoesNotExist()
        compose.onNodeWithText("Studio").assertDoesNotExist()
    }

    @Test
    fun `Down from the director reaches More like this, and Up from the cast comes back to the buttons`() {
        show(movie = film.copy(writer = null, studio = null), similar = listOf(similarFilm(8, "Harbour Lights")))

        press(Key.DirectionDown)
        press(Key.DirectionDown)
        press(Key.DirectionDown)
        compose.onNode(hasText("Harbour Lights") and hasClickAction()).assertIsFocused()

        press(Key.DirectionUp)
        press(Key.DirectionUp)
        compose.onNode(hasText("Somebody Famous") and hasClickAction()).assertIsFocused()
        press(Key.DirectionUp)
        compose.onNodeWithText("Play").assertIsFocused()
    }

    @Test
    fun `films like this one are in a row beneath the hero, and Down then OK opens one`() {
        val harbour = similarFilm(8, "Harbour Lights")
        show(movie = uncredited, similar = listOf(harbour, similarFilm(9, "Long Night")))

        compose.onNodeWithText("More like this").assertExists()
        press(Key.DirectionDown)
        compose.onNode(hasText("Harbour Lights") and hasClickAction()).assertIsFocused()
        captureScreen("movie_detail_more_like_this")

        press(Key.DirectionCenter)

        assertThat(opened).isEqualTo(harbour)
    }

    @Test
    fun `Up from the row goes back to the buttons`() {
        show(movie = uncredited, similar = listOf(similarFilm(8, "Harbour Lights")))
        press(Key.DirectionDown)

        press(Key.DirectionUp)

        compose.onNodeWithText("Play").assertIsFocused()
    }

    @Test
    fun `while films like this one load, the row holds its place, so nothing moves when they arrive`() {
        show(movie = uncredited, similar = null)
        val playTop = { compose.onNodeWithText("Play").fetchSemanticsNode().boundsInRoot.top }
        val loadingTop = playTop()

        compose.onNodeWithText("More like this").assertIsDisplayed()
        captureScreen("movie_detail_more_like_this_loading")
        press(Key.DirectionDown)
        compose.onNodeWithText("Play").assertIsFocused()

        similarFilms.value = listOf(similarFilm(8, "Harbour Lights"))
        settle()
        assertThat(playTop()).isEqualTo(loadingTop)

        similarFilms.value = emptyList()
        settle()
        assertThat(playTop()).isEqualTo(loadingTop)
        compose.onNodeWithText("More like this").assertDoesNotExist()
    }

    @Test
    fun `a film page opens with the start of the row showing beneath the hero`() {
        show(movie = uncredited, similar = listOf(similarFilm(8, "Harbour Lights")))

        compose.onNodeWithText("More like this").assertIsDisplayed()
    }

    @Test
    fun `a film part way through resumes, and says how long is left`() {
        show(resumes = true, status = CoverStatus.InProgress(progress = 0.7f, remainingMillis = 30 * 60_000L))

        compose.onNodeWithText("Resume").assertIsFocused()
        compose.onNodeWithText("Drama · 2021 · 1h 52m · 30 min left").assertIsDisplayed()
        captureScreen("movie_detail_resume")
    }

    @Test
    fun `a Watched film says so`() {
        show(status = CoverStatus.Watched)

        compose.onNodeWithText("Drama · 2021 · 1h 52m · Watched").assertIsDisplayed()
    }

    @Test
    fun `pressing Play plays the film`() {
        show()

        compose.onNodeWithText("Play").performClick()

        assertThat(plays).isEqualTo(1)
    }

    @Test
    fun `Favourite adds the film, and Remove favourite takes it off`() {
        show()
        compose.onNodeWithText("Favourite").performClick()
        favourite.value = true
        settle()
        compose.onNodeWithText("Remove favourite").performClick()

        assertThat(favouriteRequests).containsExactly(true, false).inOrder()
    }

    @Test
    fun `without add-ons there is no Add-on sources button`() {
        show()

        compose.onNodeWithText("Add-on sources").assertDoesNotExist()
    }

    @Test
    fun `with add-ons, Add-on sources comes last and opens a picker that plays the stream picked`() {
        show(addons = true)

        press(Key.DirectionRight)
        compose.onNodeWithText("Favourite").assertIsFocused()
        press(Key.DirectionRight)
        compose.onNodeWithText("Add-on sources").assertIsFocused()
        press(Key.DirectionCenter)

        compose.onNodeWithText("Choose a stream").assertIsDisplayed()
        compose.onNode(hasText("The Quiet Road 2160p") and isFocused()).assertExists()
        compose.onNodeWithText("Second add-on · 4K · 9 GB").assertIsDisplayed()
        compose.onNodeWithText("First add-on · FHD").assertIsDisplayed()
        captureScreen("movie_detail_addon_picker")

        compose.onNode(hasText("The Quiet Road 1080p") and hasClickAction()).performClick()
        settle()

        assertThat(playedStream).isEqualTo(streams[1].stream)
        compose.onNodeWithText("Choose a stream").assertDoesNotExist()
    }

    @Test
    fun `a film kept from an add-on plays through the picker, with no button of its own for it`() {
        versions = PageVersions(playsThroughAddons = true)
        show(movie = film.copy(streamUrl = ""), addons = true)

        compose.onNodeWithText("Play").assertIsFocused()
        compose.onNodeWithText("Add-on sources").assertDoesNotExist()
        captureScreen("movie_detail_addon_film")
        press(Key.DirectionCenter)

        compose.onNodeWithText("Choose a stream").assertIsDisplayed()
        assertThat(plays).isEqualTo(0)
    }

    @Test
    fun `with no add-on left, a film kept from one can't be played`() {
        versions = PageVersions(playsThroughAddons = true)
        show(movie = film.copy(streamUrl = ""), addons = false)

        compose.onNodeWithText("Play").assertIsNotEnabled()
        compose.onNodeWithText("Favourite").assertIsFocused()
    }

    @Test
    fun `while no add-on has answered, the picker says it's looking`() {
        findStreams = { emptyFlow() }
        show(addons = true)

        compose.onNodeWithText("Add-on sources").performClick()
        settle()

        compose.onNodeWithText("Finding streams from your add-ons…").assertIsDisplayed()
        captureScreen("movie_detail_addon_picker_loading")
    }

    @Test
    fun `streams show as the first add-ons answer, with a line while the rest are asked`() {
        val answers = MutableStateFlow<AddonSearch>(AddonSearch.Results(streams.take(1), finished = false))
        findStreams = { answers }
        show(addons = true)

        compose.onNodeWithText("Add-on sources").performClick()
        settle()

        compose.onNode(hasText("The Quiet Road 2160p") and isFocused()).assertExists()
        compose.onNodeWithText("Still asking your other add-ons…").assertIsDisplayed()
        compose.onNodeWithText("The Quiet Road 1080p").assertDoesNotExist()
        captureScreen("movie_detail_addon_picker_partial")

        answers.value = AddonSearch.Results(streams, finished = true)
        settle()

        compose.onNodeWithText("The Quiet Road 1080p").assertIsDisplayed()
        compose.onNodeWithText("Still asking your other add-ons…").assertDoesNotExist()
    }

    @Test
    fun `a picker closed while it is still looking stays closed when the streams arrive`() {
        val answers = MutableStateFlow<AddonSearch?>(null)
        findStreams = { answers.filterNotNull() }
        show(addons = true)
        compose.onNodeWithText("Add-on sources").performClick()
        compose.onNodeWithText("Finding streams from your add-ons…").assertIsDisplayed()

        Espresso.pressBack()
        settle()
        answers.value = AddonSearch.Results(streams, finished = true)
        settle()

        compose.onNodeWithText("Choose a stream").assertDoesNotExist()
    }

    @Test
    fun `when every add-on has answered with nothing, the picker says none were found`() {
        findStreams = { flowOf(AddonSearch.Results(emptyList(), finished = true)) }
        show(addons = true)

        compose.onNodeWithText("Add-on sources").performClick()
        settle()

        compose.onNodeWithText("No streams found from your add-ons for this title.").assertIsDisplayed()
        compose.onNodeWithText("Try again").assertDoesNotExist()
        captureScreen("movie_detail_addon_picker_none")
    }

    @Test
    fun `when an add-on couldn't be reached and none had streams, the picker says why, and Try again asks again`() {
        var asked = 0
        findStreams = {
            asked++
            if (asked == 1) {
                flowOf(
                    AddonSearch.Results(
                        emptyList(),
                        finished = true,
                        failure = ViewerError(
                            "Can't reach the add-on. Check this TV is online and the address is right.",
                            "UnknownHost",
                        ),
                    ),
                )
            } else {
                flowOf(AddonSearch.Results(streams, finished = true))
            }
        }
        show(addons = true)
        compose.onNodeWithText("Add-on sources").requestFocus()
        press(Key.DirectionCenter)

        compose.onNodeWithText("Can't reach the add-on. Check this TV is online and the address is right.")
            .assertIsDisplayed()
        compose.onNode(hasTestTag(ERROR_DETAIL_TAG) and hasText("UnknownHost"), useUnmergedTree = true).assertExists()
        compose.onNodeWithText("No streams found", substring = true).assertDoesNotExist()
        compose.onNode(hasText("Try again") and isFocused()).assertExists()
        captureScreen("movie_detail_addon_picker_failed")

        // The page's own button keeps focus in its window under the dialog, so OK goes to the dialog's.
        compose.onNode(hasText("Try again") and isFocused()).performKeyInput { pressKey(Key.DirectionCenter) }
        settle()

        assertThat(asked).isEqualTo(2)
        compose.onNode(hasText("The Quiet Road 2160p") and hasClickAction()).assertExists()
    }

    @Test
    fun `a film with no IMDb match says the add-ons can't look it up, with nothing to try again`() {
        findStreams = {
            flowOf(
                AddonSearch.CantAsk(
                    ViewerError("Your add-ons can't look this film up, as it has no match on IMDb.", "NoImdbMatch"),
                    canRetry = false,
                ),
            )
        }
        show(addons = true)

        compose.onNodeWithText("Add-on sources").performClick()
        settle()

        compose.onNodeWithText("Your add-ons can't look this film up, as it has no match on IMDb.").assertIsDisplayed()
        compose.onNode(hasTestTag(ERROR_DETAIL_TAG) and hasText("NoImdbMatch"), useUnmergedTree = true).assertExists()
        compose.onNodeWithText("Try again").assertDoesNotExist()
        captureScreen("movie_detail_addon_picker_no_match")
    }

    private fun showWith(shown: PageVersions) {
        versions = shown
        show()
    }

    private fun choice(id: Long, traits: VersionTraits, playable: Boolean = true, playing: Boolean = false) =
        VodQualities.Choice(MediaKey.Movie(id), traits, playable, playing)

    private val threeVersions = PageVersions(
        choices = listOf(
            choice(1, VersionTraits(qualityRank = 400, qualityLabel = "4K"), playable = false),
            choice(2, VersionTraits(qualityRank = 200, qualityLabel = "HD", language = "de")),
            choice(3, VersionTraits(), playing = true),
        ),
    )

    @Test
    fun `the Quality pill lists the Versions that differ, greying out what this TV can't play`() {
        showWith(threeVersions)

        compose.onNodeWithText("Quality").performClick()
        settle()

        compose.onNodeWithText("4K").assertIsNotEnabled()
        compose.onNodeWithText("HD · German").assertIsEnabled()
        compose.onNodeWithText("Other").assertIsSelected()
        captureScreen("movie_detail_quality_pill")
    }

    @Test
    fun `choosing in the Quality pill chooses that Version`() {
        showWith(threeVersions)

        compose.onNodeWithText("Quality").performClick()
        settle()
        compose.onNodeWithText("HD · German").performClick()
        settle()

        assertThat(chosen).containsExactly(MediaKey.Movie(2))
    }

    @Test
    fun `there's no Quality pill when the Versions are alike`() {
        showWith(PageVersions(choices = listOf(choice(3, VersionTraits(), playing = true))))

        compose.onNodeWithText("Quality").assertDoesNotExist()
    }

    @Test
    fun `there's no Quality pill when this TV can't play two of the Versions`() {
        showWith(
            PageVersions(
                choices = listOf(
                    choice(1, VersionTraits(qualityRank = 400, qualityLabel = "4K"), playable = false),
                    choice(2, VersionTraits(qualityRank = 400, qualityLabel = "4K", language = "de"), playable = false),
                ),
                cantPlay = "4K",
            ),
        )

        compose.onNodeWithText("Quality").assertDoesNotExist()
        captureScreen("film_page_no_quality_button")
    }

    @Test
    fun `there's no Quality pill when only one of the Versions plays on this TV`() {
        showWith(
            PageVersions(
                choices = listOf(
                    choice(1, VersionTraits(qualityRank = 400, qualityLabel = "4K"), playable = false),
                    choice(3, VersionTraits(), playing = true),
                ),
            ),
        )

        compose.onNodeWithText("Quality").assertDoesNotExist()
    }

    @Test
    fun `Play is off and says why when nothing of the film plays here`() {
        showWith(PageVersions(cantPlay = "4K"))

        compose.onNodeWithText("Play").assertIsNotEnabled()
        compose.onNodeWithText("can't play 4K", substring = true).assertIsDisplayed()
        captureScreen("movie_detail_cant_play")
    }

    @Test
    fun `Play asks before another language, and agreeing chooses it and plays`() {
        showWith(PageVersions(otherLanguage = PageVersions.OtherLanguage(MediaKey.Movie(2), "de")))

        compose.onNodeWithText("Play").performClick()
        settle()
        captureScreen("movie_detail_language_question")
        assertThat(plays).isEqualTo(0)
        compose.onNodeWithText("Play in German").performClick()
        settle()

        assertThat(chosen).containsExactly(MediaKey.Movie(2))
        assertThat(plays).isEqualTo(1)
    }

    @Test
    fun `a film page opened for a film that isn't stored says it's gone, and its highlighted Back leaves`() {
        // The whole screen with its real view model, which only this state needs (#495).
        val app = testGraph.app
        val graph = testGraph.graph
        runBlocking { graph.database.sources().insert(PANEL_SOURCE) }
        var left = false
        val viewModel = testGraph.viewModel { VodViewModel(app, graph) }
        compose.setTvContent {
            MovieDetailScreen(
                movieId = 404,
                onPlay = {},
                onPlayAddonStream = {},
                onOpenMovie = {},
                onOpenPerson = {},
                onBack = { left = true },
                viewModel = viewModel,
            )
        }

        compose.onAllNodes(hasText(app.getString(R.string.error_gone))).onFirst().assertExists()
        compose.onNodeWithText(app.getString(R.string.common_back)).assertIsFocused()
        captureScreen("movie_detail_gone")
        compose.onRoot().performKeyInput { pressKey(Key.DirectionCenter) }
        compose.waitForIdle()

        assertThat(left).isTrue()
    }
}
