/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertIsNotFocused
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasScrollToNodeAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.requestFocus
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.height
import androidx.compose.ui.unit.width
import androidx.media3.common.C
import app.opentv.data.model.Channel
import app.opentv.data.model.Movie
import app.opentv.data.model.Series
import app.opentv.data.repo.CoverStatus
import app.opentv.data.repo.LineupChannel
import app.opentv.data.repo.LivePreview
import app.opentv.data.repo.MediaKey
import app.opentv.data.repo.VodTitle
import app.opentv.ui.Cover
import app.opentv.ui.HomeChannel
import app.opentv.ui.HomeContent
import app.opentv.ui.LiveNow
import app.opentv.ui.LivePreviewPlayer
import app.opentv.ui.LiveTile
import app.opentv.ui.ResumeItem
import app.opentv.ui.SeasonEpisode
import app.opentv.ui.options.LeaveScreen
import app.opentv.ui.options.LocalOpenOptions
import app.opentv.ui.rememberLivePreviewPlayer
import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

// Plain Application: OpenTvApp schedules WorkManager on start, which lone composables don't need.
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = TV_1080P, application = android.app.Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@OptIn(ExperimentalTestApi::class)
class HomeContentTest {

    @get:Rule
    val compose = createComposeRule()

    @get:Rule
    val artwork = PlaceholderArtwork()

    private val resume = listOf(
        ResumeItem(
            MediaKey.Movie(9),
            "Collateral",
            posterUrl = "https://example.com/9.jpg",
            progress = 0.4f,
        ),
    )

    private fun movie(id: Long, name: String) = Movie(
        id = id,
        sourceId = 1,
        streamId = "$id",
        name = name,
        categoryId = null,
        posterUrl = "https://example.com/$id.jpg",
        rating = null,
        year = null,
        plot = null,
        durationSeconds = null,
        containerExtension = "mp4",
        streamUrl = "",
    )

    private fun series(id: Long, name: String) = Series(
        id = id,
        sourceId = 1,
        seriesId = "$id",
        name = name,
        categoryId = null,
        posterUrl = "https://example.com/$id.jpg",
        rating = null,
        year = null,
        plot = null,
    )

    private fun channel(id: Long, name: String) = Channel(
        id = id,
        sourceId = 1,
        streamId = "$id",
        name = name,
        streamUrl = "https://example.com/$id.ts",
        categoryId = null,
        logoUrl = "https://example.com/$id.png",
        epgChannelId = null,
        number = null,
    )

    private fun favourite(id: Long, name: String, nowOn: String? = null) = channel(id, name).let {
        HomeChannel(LineupChannel(it, listOf(it), favourite = true), nowOn)
    }

    /** The shows in a test's mixed list of covers, for their own row. */
    private fun List<Cover>.shows() = filter { it.title is VodTitle.OfSeries }

    /** The movies in a test's mixed list of covers, for their own row. */
    private fun List<Cover>.movies() = filter { it.title is VodTitle.OfMovie }

    private fun cover(movie: Movie, status: CoverStatus? = null) = Cover(VodTitle.OfMovie(movie), status)

    private fun cover(series: Series, status: CoverStatus? = null) = Cover(VodTitle.OfSeries(series), status)

    /** The Live Preview a test's Home leads with, if any. */
    private var live by mutableStateOf<LiveTile?>(null)

    /** The channel a test's Live Preview last opened full screen. */
    private var watched: Channel? = null

    /** Whether a test's Live Preview placeholder opened the Live tab. */
    private var openedLive = false

    private val channel4 = channel(40, "Channel 4")

    private fun playable(error: String? = null) = LiveTile(
        LiveNow(LivePreview.Playable(channel4), nowTitle = "Countdown", nextTitle = "A Place in the Sun"),
        player = null,
        error = error,
    )

    /** What a test's Home did when its cards were selected, in order. */
    private val events = mutableListOf<String>()

    @Test
    fun `the Live Preview has no sound track`() {
        lateinit var preview: LivePreviewPlayer
        compose.setContent { preview = rememberLivePreviewPlayer(channel4) }
        compose.waitForIdle()

        assertThat(preview.player!!.trackSelectionParameters.disabledTrackTypes).contains(C.TRACK_TYPE_AUDIO)
    }

    /** The channel a test's Home last played. */
    private var played: Channel? = null

    /** False when a test's Home was picked in the side menu, where the highlight stays. */
    private var takeFocus = true

    /** The movie a test's Home last opened. */
    private var opened: Movie? = null

    private val recent = listOf(cover(movie(11, "Drive")), cover(series(13, "Shogun")))

    /**
     * Brings [text] fully onto the screen: the list composes its row, then scrolls on by however far
     * the card still hangs below it, since a row whose title peeks in counts as shown.
     */
    private fun scrollTo(text: String) {
        val list = compose.onAllNodes(hasScrollToNodeAction())[0]
        list.performScrollToNode(hasText(text))
        val below =
            compose.onNodeWithText(text).getUnclippedBoundsInRoot().bottom - list.getUnclippedBoundsInRoot().bottom
        if (below > 0.dp) {
            val by = with(compose.density) { below.toPx() }
            list.performSemanticsAction(SemanticsActions.ScrollBy) { it(0f, by) }
        }
    }

    private fun setHome(
        resume: List<ResumeItem> = this.resume,
        favourites: List<Cover> = listOf(cover(series(1, "Severance")), cover(movie(2, "Heat"))),
        channels: List<HomeChannel> = listOf(favourite(3, "BBC One")),
        recent: List<Cover> = emptyList(),
        favouritesNow: () -> List<Cover> = { favourites },
    ) = compose.setTvContent {
        CompositionLocalProvider(LocalOpenOptions provides { _, leave -> leaveThroughOptions = leave }) {
            HomeContent(
                live = live,
                onWatchLive = {
                    events += "watch"
                    watched = it
                },
                onOpenLive = { openedLive = true },
                stopPreview = { events += "stop" },
                continueWatching = resume,
                favouriteShows = favouritesNow().shows(),
                favouriteMovies = favouritesNow().movies(),
                favouriteChannels = channels,
                recentShows = recent.shows(),
                recentMovies = recent.movies(),
                onResume = { events += "resume" },
                onOpenSeries = { events += "series" },
                onOpenMovie = {
                    events += "movie"
                    opened = it
                },
                onPlayChannel = {
                    events += "channel"
                    played = it
                },
                takeFocus = takeFocus,
            )
        }
    }

    /** How the options panel last opened on Home would leave it. */
    private var leaveThroughOptions: LeaveScreen? = null

    private fun openOptionsOn(title: String) {
        compose.onNodeWithText(title).requestFocus().performKeyInput { pressKey(Key.Menu) }
        compose.waitForIdle()
    }

    private fun leavingThroughOptionsStopsThePreviewFirst(title: String) {
        setHome()
        openOptionsOn(title)

        leaveThroughOptions!! { events += "left" }

        assertThat(events).containsExactly("stop", "left").inOrder()
    }

    @Test
    fun `leaving through a Continue Watching card's options stops the Live Preview first`() =
        leavingThroughOptionsStopsThePreviewFirst("Collateral")

    @Test
    fun `leaving through a show's options stops the Live Preview first`() =
        leavingThroughOptionsStopsThePreviewFirst("Severance")

    @Test
    fun `leaving through a movie's options stops the Live Preview first`() =
        leavingThroughOptionsStopsThePreviewFirst("Heat")

    @Test
    fun `back from leaving through a card's options, Home highlights that card`() {
        setHomeToLeave(movies = { listOf(movie(2, "Heat"), movie(4, "Ronin")) })
        openOptionsOn("Ronin")

        leaveThroughOptions!! { home = false }
        compose.waitForIdle()
        home = true
        compose.waitForIdle()

        compose.onNodeWithText("Ronin").assertIsFocused()
    }

    /** Whether a test's Home is on screen; false while the viewer is off in the player or on a title's page. */
    private var home by mutableStateOf(true)

    /**
     * Shows Home the way the app's navigation does: going to another screen drops Home but keeps its
     * saved state, which comes back when Home does.
     */
    private fun setHomeToLeave(
        continueWatching: () -> List<ResumeItem> = { resume },
        movies: () -> List<Movie> = { listOf(movie(2, "Heat")) },
        recent: List<Cover> = emptyList(),
    ) = compose.setTvContent {
        val saved = rememberSaveableStateHolder()
        if (home) {
            saved.SaveableStateProvider("home") {
                CompositionLocalProvider(LocalOpenOptions provides { _, leave -> leaveThroughOptions = leave }) {
                    HomeContent(
                        live = live,
                        onWatchLive = { watched = it },
                        continueWatching = continueWatching(),
                        favouriteShows = listOf(cover(series(1, "Severance"))),
                        favouriteMovies = movies().map(::cover),
                        favouriteChannels = listOf(favourite(3, "BBC One"), favourite(5, "BBC Two")),
                        recentShows = recent.shows(),
                        recentMovies = recent.movies(),
                        onResume = {},
                        onOpenSeries = {},
                        onOpenMovie = { opened = it },
                        onPlayChannel = { played = it },
                        takeFocus = homeTakesFocus,
                    )
                }
            }
        }
    }

    /** False when a test's Home comes back while the side menu has the highlight. */
    private var homeTakesFocus by mutableStateOf(true)

    private fun resumeItem(id: Long, title: String) = ResumeItem(
        MediaKey.Movie(id),
        title,
        posterUrl = "https://example.com/$id.jpg",
        progress = 0.4f,
    )

    /** Leaves Home without picking anything (from the side menu, say) and comes back. */
    private fun leaveAndComeBack() {
        home = false
        compose.waitForIdle()
        home = true
        compose.waitForIdle()
    }

    /** Picks [title] on Home, leaves for the screen it opens, and comes back. */
    private fun pickAndComeBack(title: String, whileAway: () -> Unit = {}) {
        scrollTo(title)
        compose.onNodeWithText(title).performClick()
        home = false
        compose.waitForIdle()
        whileAway()
        home = true
        compose.waitForIdle()
    }

    private fun topOf(text: String) = compose.onNodeWithText(text).getUnclippedBoundsInRoot().top

    @Test
    fun `rows run Continue Watching, Recently Added by kind, then Favourites by kind, then channels`() {
        setHome(recent = recent)

        val order = listOf(
            "Continue watching",
            "Recently added shows",
            "Recently added movies",
            "Favourite shows",
            "Favourite movies",
            "Favourite channels",
        )
        // Six rows overflow a TV screen: bring each in under the one before it.
        order.zipWithNext { above, below ->
            scrollTo(below)
            assertThat(topOf(above)).isLessThan(topOf(below))
        }
        compose.onNodeWithText("BBC One").assertExists()
    }

    @Test
    fun `Favourite shows and Favourite movies are rows of their own`() {
        setHome(favourites = listOf(cover(movie(2, "Heat")), cover(series(1, "Severance"))))

        assertThat(topOf("Severance")).isLessThan(topOf("Heat"))
    }

    @Test
    fun `selecting a Recently Added movie opens it`() {
        setHome(recent = recent)
        scrollTo("Drive")

        compose.onNodeWithText("Drive").performClick()

        assertThat(opened).isEqualTo(movie(11, "Drive"))
    }

    @Test
    fun `with only Recently Added to show, Home shows it rather than the hint`() {
        setHome(resume = emptyList(), favourites = emptyList(), channels = emptyList(), recent = recent)

        compose.onNodeWithText("Things you watch and favourite will show up here").assertDoesNotExist()
        compose.onNodeWithText("Shogun").assertIsFocused()
    }

    @Test
    fun `a row with nothing in it is left out`() {
        setHome(favourites = emptyList())

        compose.onNodeWithText("Favourite shows").assertDoesNotExist()
        compose.onNodeWithText("Favourite movies").assertDoesNotExist()
        assertThat(topOf("Continue watching")).isLessThan(topOf("Favourite channels"))
        captureScreen("home_empty_rows_hidden")
    }

    @Test
    fun `a Profile with nothing watched or favourited gets a hint instead of rows`() {
        setHome(resume = emptyList(), favourites = emptyList(), channels = emptyList())

        compose.onNodeWithText("Things you watch and favourite will show up here").assertExists()
        captureScreen("home_hint")
    }

    @Test
    fun `Home opens with the first card of the top row highlighted`() {
        setHome()

        compose.onNodeWithText("Collateral").assertIsFocused()
        captureScreen("home_initial")
    }

    @Test
    fun `Home opens with the Live Preview highlighted, above Continue Watching, with what's on`() {
        live = playable()
        setHome()

        compose.onNodeWithText("Channel 4").assertIsFocused()
        compose.onNodeWithText("Countdown", substring = true).assertExists()
        compose.onNodeWithText("A Place in the Sun", substring = true).assertExists()
        assertThat(topOf("Channel 4")).isLessThan(topOf("Collateral"))
        captureScreen("home_live_preview")
    }

    @Test
    fun `every card on Home stops the preview before it opens`() {
        live = playable()
        setHome(recent = recent)

        fun selecting(card: String): List<String> {
            events.clear()
            scrollTo(card)
            compose.onNodeWithText(card).performClick()
            return events.toList()
        }

        assertThat(selecting("Channel 4")).containsExactly("stop", "watch").inOrder()
        assertThat(selecting("Collateral")).containsExactly("stop", "resume").inOrder()
        assertThat(selecting("Severance")).containsExactly("stop", "series").inOrder()
        assertThat(selecting("Heat")).containsExactly("stop", "movie").inOrder()
        assertThat(selecting("BBC One")).containsExactly("stop", "channel").inOrder()
        assertThat(selecting("Drive")).containsExactly("stop", "movie").inOrder()
        assertThat(selecting("Shogun")).containsExactly("stop", "series").inOrder()
    }

    @Test
    fun `selecting the Live Preview watches its channel full screen`() {
        live = playable()
        setHome()

        compose.onNodeWithText("Channel 4").performClick()

        assertThat(watched).isEqualTo(channel4)
    }

    @Test
    fun `a Live Preview of a channel gone from the list says so, and opens the Live tab`() {
        live = LiveTile(LiveNow(LivePreview.Gone("Channel 4", null), null, null), player = null, error = null)
        setHome()

        compose.onNodeWithText("No longer in your channel list").assertExists()
        compose.onNodeWithText("Channel 4").performClick()

        assertThat(openedLive).isTrue()
        assertThat(watched).isNull()
        captureScreen("home_live_preview_placeholder")
    }

    @Test
    fun `a Live Preview that can't play says why, keeping what's on`() {
        live = playable(error = "The provider refused the stream (403). Check your subscription.")
        setHome()

        compose.onNodeWithText("The provider refused the stream", substring = true).assertExists()
        compose.onNodeWithText("Countdown", substring = true).assertExists()
        captureScreen("home_live_preview_error")
    }

    @Test
    fun `a show with a New Episode is labelled in Home's Continue Watching`() {
        val newEpisode = ResumeItem(
            MediaKey.Episode(31),
            "Severance",
            posterUrl = "https://example.com/31.jpg",
            progress = null,
            episode = SeasonEpisode(season = 3, episode = 1),
            newEpisode = true,
        )
        setHome(resume = listOf(newEpisode) + resume)

        compose.onNodeWithText("New episode", useUnmergedTree = true).assertExists()
        captureScreen("home_continue_watching_new_episode")
    }

    @Test
    fun `with nothing to continue, the first Favourite show is highlighted`() {
        setHome(resume = emptyList())

        compose.onNodeWithText("Severance").assertIsFocused()
        captureScreen("home_no_continue_watching")
    }

    @Test
    fun `back from a title's page, Home highlights the card that was picked`() {
        setHomeToLeave(movies = { listOf(movie(2, "Heat"), movie(4, "Ronin")) })

        pickAndComeBack("Ronin")

        compose.onNodeWithText("Ronin").assertIsFocused()
    }

    @Test
    fun `back from a title whose card has gone, Home highlights the card now in its place`() {
        var movies by mutableStateOf(listOf(movie(2, "Heat"), movie(4, "Ronin"), movie(6, "Thief")))
        setHomeToLeave(movies = { movies })

        pickAndComeBack("Ronin") { movies = listOf(movie(2, "Heat"), movie(6, "Thief")) }

        compose.onNodeWithText("Thief").assertIsFocused()
    }

    @Test
    fun `back from the player, Home highlights the title played even once Continue Watching puts it first`() {
        var cards by mutableStateOf(
            listOf(resumeItem(9, "Collateral"), resumeItem(7, "Insomnia"), resumeItem(8, "Memento")),
        )
        setHomeToLeave(continueWatching = { cards })

        pickAndComeBack("Memento") {
            cards = listOf(resumeItem(8, "Memento"), resumeItem(9, "Collateral"), resumeItem(7, "Insomnia"))
        }

        compose.onNodeWithText("Memento").assertIsFocused()
    }

    @Test
    fun `back from a title picked under the Live Preview, Home highlights the title`() {
        live = playable()
        setHomeToLeave(movies = { listOf(movie(2, "Heat"), movie(4, "Ronin")) })
        scrollTo("Ronin")

        pickAndComeBack("Ronin")

        compose.onNodeWithText("Ronin").assertIsFocused()
    }

    @Test
    fun `back from switching to a Profile that was watching live, Home highlights its Live Preview`() {
        setHomeToLeave()

        home = false
        compose.waitForIdle()
        live = playable()
        home = true
        compose.waitForIdle()

        compose.onNodeWithText("Channel 4").assertIsFocused()
    }

    @Test
    fun `coming back again without picking anything, Home opens on its first card`() {
        setHomeToLeave(movies = { listOf(movie(2, "Heat"), movie(4, "Ronin")) })
        pickAndComeBack("Ronin")

        leaveAndComeBack()

        compose.onNodeWithText("Collateral").assertIsFocused()
    }

    @Test
    fun `a pick Home came back to with the highlight on the side menu isn't kept for later`() {
        setHomeToLeave(movies = { listOf(movie(2, "Heat"), movie(4, "Ronin")) })
        homeTakesFocus = false
        pickAndComeBack("Ronin")
        homeTakesFocus = true

        leaveAndComeBack()

        compose.onNodeWithText("Collateral").assertIsFocused()
    }

    @Test
    fun `back from the player, Home highlights the channel that was playing`() {
        setHomeToLeave()
        scrollTo("BBC Two")

        pickAndComeBack("BBC Two")

        compose.onNodeWithText("BBC Two").assertIsFocused()
    }

    @Test
    fun `back from a show's page, Home highlights it even far down in Recently Added`() {
        setHomeToLeave(recent = recent)
        scrollTo("Shogun")

        pickAndComeBack("Shogun")

        compose.onNodeWithText("Shogun").assertIsFocused()
        captureScreen("home_back_to_picked")
    }

    @Test
    fun `a Favourite channel is a square tile with what's on now underneath, or just its name`() {
        // Nothing highlighted: a highlighted tile's lift moves where it's reported to be.
        takeFocus = false
        setHome(
            resume = emptyList(),
            favourites = emptyList(),
            channels = listOf(favourite(3, "BBC One", nowOn = "Pointless"), favourite(5, "BBC Two")),
        )

        val tile = compose.onNodeWithText("BBC One").getUnclippedBoundsInRoot()
        assertThat(tile.width).isEqualTo(118.dp)
        assertThat(tile.height).isEqualTo(118.dp)
        assertThat(topOf("Pointless")).isGreaterThan(tile.bottom)
        // Lined up by their squares, though only one has a second line underneath.
        assertThat(topOf("BBC Two")).isEqualTo(tile.top)
        assertThat(compose.onAllNodes(hasText("Pointless")).fetchSemanticsNodes()).hasSize(1)
        captureScreen("home_favourite_channels")
    }

    @Test
    fun `Recently Added covers say where the Profile is, and an unstarted title shows only its name`() {
        val episode = testEpisode(4, 3)
        setHome(
            resume = emptyList(),
            favourites = emptyList(),
            recent = listOf(
                cover(series(1, "Severance"), CoverStatus.InProgress(0.5f, remainingMillis = 1_320_000, episode)),
                cover(movie(2, "Heat"), CoverStatus.InProgress(0.8f, remainingMillis = 1_320_000)),
                cover(series(4, "Slow Horses"), CoverStatus.UpNext(testEpisode(2, 1))),
                cover(series(6, "Taskmaster"), CoverStatus.NewEpisode),
                cover(movie(7, "Ronin"), CoverStatus.Watched),
                cover(movie(8, "Thief")),
            ),
            channels = emptyList(),
        )

        // An episode and the time left are two lines, split where they meet rather than wherever
        // a narrow cover happens to wrap them.
        compose.onNodeWithText("S4 E3", useUnmergedTree = true).assertExists()
        assertThat(compose.onAllNodes(hasText("22 min left"), useUnmergedTree = true).fetchSemanticsNodes()).hasSize(2)
        compose.onNodeWithText("S2 E1", useUnmergedTree = true).assertExists()
        compose.onNodeWithText("New episode", useUnmergedTree = true).assertExists()
        compose.onNodeWithText("Watched", useUnmergedTree = true).assertExists()
        compose.onNode(hasText("Thief") and hasClickAction()).assertTextEquals("Thief")
        captureScreen("home_cover_status")
    }

    @Test
    fun `a Favourite shows only its title, however far through it the Profile is`() {
        setHome(
            resume = emptyList(),
            favourites = listOf(
                cover(
                    series(1, "Severance"),
                    CoverStatus.InProgress(0.5f, remainingMillis = 1_320_000, testEpisode(4, 3)),
                ),
                cover(movie(7, "Ronin"), CoverStatus.Watched),
            ),
            channels = emptyList(),
        )

        compose.onNode(hasText("Severance") and hasClickAction()).assertTextEquals("Severance")
        compose.onNode(hasText("Ronin") and hasClickAction()).assertTextEquals("Ronin")
    }

    private val threeToContinue =
        listOf(resumeItem(9, "Collateral"), resumeItem(7, "Insomnia"), resumeItem(8, "Memento"))

    private val threeFavourites =
        listOf(cover(movie(1, "Sicario")), cover(movie(2, "Heat")), cover(movie(4, "Ronin")))

    private fun press(key: Key) {
        compose.onRoot().performKeyInput { pressKey(key) }
        compose.waitForIdle()
    }

    @Test
    fun `Down from the large first card lands on the first Favourite`() {
        setHome(resume = threeToContinue, favourites = threeFavourites)
        assertThat(compose.onNodeWithText("Collateral").getUnclippedBoundsInRoot().width).isEqualTo(300.dp)

        press(Key.DirectionDown)

        compose.onNodeWithText("Sicario").assertIsFocused()
    }

    @Test
    fun `coming back into a row with Up or Down lands on the card last highlighted there`() {
        setHome(resume = threeToContinue, favourites = threeFavourites)
        press(Key.DirectionRight)
        press(Key.DirectionRight)
        press(Key.DirectionDown)
        press(Key.DirectionRight)
        press(Key.DirectionRight)
        compose.onNodeWithText("Ronin").assertIsFocused()

        press(Key.DirectionDown)
        press(Key.DirectionUp)
        compose.onNodeWithText("Ronin").assertIsFocused()

        press(Key.DirectionUp)
        compose.onNodeWithText("Memento").assertIsFocused()
    }

    @Test
    fun `coming back into a row lands on the same card even after the row has moved round`() {
        var favourites by mutableStateOf(threeFavourites)
        setHome(resume = threeToContinue, favouritesNow = { favourites })
        press(Key.DirectionDown)
        press(Key.DirectionRight)
        press(Key.DirectionRight)
        press(Key.DirectionDown)

        favourites = listOf(cover(movie(6, "Thief"))) + threeFavourites
        compose.waitForIdle()
        press(Key.DirectionUp)

        compose.onNodeWithText("Ronin").assertIsFocused()
    }

    @Test
    fun `Up from a small card in the top row stays where it is`() {
        setHome(resume = threeToContinue, favourites = threeFavourites)
        press(Key.DirectionRight)
        press(Key.DirectionRight)

        press(Key.DirectionUp)

        compose.onNodeWithText("Memento").assertIsFocused()
    }

    @Test
    fun `Down from the Live Preview lands on the first Continue Watching card, which is no longer large`() {
        live = playable()
        setHome(resume = threeToContinue, favourites = threeFavourites)
        assertThat(compose.onNodeWithText("Collateral").getUnclippedBoundsInRoot().width).isEqualTo(186.dp)

        press(Key.DirectionDown)

        compose.onNodeWithText("Collateral").assertIsFocused()
        captureScreen("home_live_preview_with_favourites")
    }

    @Test
    fun `a title's Quality from its provider's name shows on its cover and its Continue Watching card`() {
        setHome(
            resume = listOf(resumeItem(9, "Dune").copy(name = "4K-EN - Dune")),
            favourites = listOf(cover(movie(2, "EN - Dragons FHD")), cover(movie(4, "Ronin"))),
            channels = emptyList(),
        )

        compose.onNodeWithText("4K", useUnmergedTree = true).assertExists()
        compose.onNodeWithText("FHD", useUnmergedTree = true).assertExists()
        compose.onNode(hasText("Ronin") and hasClickAction()).assertTextEquals("Ronin")
    }

    @Test
    fun `selecting a Favourite channel plays it`() {
        setHome(resume = emptyList(), favourites = emptyList())

        compose.onNodeWithText("BBC One").performClick()

        assertThat(played).isEqualTo(channel(3, "BBC One"))
    }

    @Test
    fun `Home picked from the side menu leaves the highlight on the menu`() {
        takeFocus = false

        setHome()

        compose.onNodeWithText("Collateral").assertIsNotFocused()
    }

    @Test
    fun `when the highlighted card's row goes, the highlight moves to the row that takes its place`() {
        var resume by mutableStateOf(this.resume)
        compose.setTvContent {
            HomeContent(
                continueWatching = resume,
                favouriteShows = listOf(cover(series(1, "Severance"))),
                favouriteMovies = emptyList(),
                favouriteChannels = emptyList(),
                onResume = {},
                onOpenSeries = {},
                onOpenMovie = {},
                onPlayChannel = {},
                takeFocus = true,
            )
        }
        compose.onNodeWithText("Collateral").assertIsFocused()

        resume = emptyList()

        compose.onNodeWithText("Severance").assertIsFocused()
    }

    @Test
    fun `removing the last Continue Watching card moves the highlight to the row that takes its place`() {
        var continueWatching by mutableStateOf(resume)
        compose.setTvContent {
            HomeContent(
                continueWatching = continueWatching,
                favouriteShows = listOf(cover(series(1, "Severance"))),
                favouriteMovies = emptyList(),
                favouriteChannels = emptyList(),
                onResume = {},
                onOpenSeries = {},
                onOpenMovie = {},
                onPlayChannel = {},
                takeFocus = true,
            )
        }
        compose.onNode(hasText("Collateral") and hasClickAction()).assertIsFocused()

        continueWatching = emptyList()

        compose.onNode(hasText("Severance") and hasClickAction()).assertIsFocused()
    }
}
