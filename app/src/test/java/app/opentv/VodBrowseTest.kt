/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv

import android.content.pm.PackageManager
import android.view.KeyEvent
import android.view.View
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isFocused
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.requestFocus
import app.opentv.data.model.Category
import app.opentv.data.model.Movie
import app.opentv.data.model.Series
import app.opentv.data.model.StreamKind
import app.opentv.data.parser.displayTitle
import app.opentv.ui.MainShell
import app.opentv.ui.Tab
import app.opentv.ui.VodViewModel
import app.opentv.ui.visibleTabs
import app.opentv.ui.vod.MoviesScreen
import app.opentv.ui.vod.ProvidersCarry
import app.opentv.ui.vod.SeriesScreen
import coil.size.Size
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Movies and Shows on the remote: where the Highlight goes, the Categories Sidebar and the backdrop (#161). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = TV_1080P, application = android.app.Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@OptIn(ExperimentalTestApi::class)
class VodBrowseTest {

    private val compose = createAndroidComposeRule<ComponentActivity>()

    @get:Rule
    val testGraph = TestGraph(compose)

    @get:Rule
    val artwork = PlaceholderArtwork()

    private val app = testGraph.app
    private val graph = testGraph.graph

    // Made once a test has saved its library, as the app makes it once a sync has: made first, it
    // works out its rows from an empty library, and the screen's reload waits behind that.
    private val viewModel by lazy { testGraph.viewModel { VodViewModel(app, graph) } }

    private lateinit var view: View
    private val device = DeviceKeyboard(compose) { view }

    private fun movie(id: Long, name: String, category: String = "action", added: Long = id) = Movie(
        id = id,
        sourceId = 1,
        streamId = "$id",
        name = name,
        categoryId = category,
        posterUrl = "https://example.com/$id.jpg",
        rating = null,
        year = null,
        plot = "What happens in $name.",
        durationSeconds = null,
        containerExtension = "mkv",
        streamUrl = "https://example.com/$id",
        addedMillis = added,
    )

    /** Movies' Recently added row runs newest first: "Heat" then "Ronin", "Alien", "Brazil". */
    private fun library() = runBlocking {
        graph.database.categories().upsertAll(
            listOf(
                Category("action", 1, "Action", StreamKind.MOVIE, sortIndex = 0),
                Category("comedy", 1, "Comedy", StreamKind.MOVIE, sortIndex = 1),
            ),
        )
        graph.database.movies().upsertAll(
            listOf(
                movie(1, "Brazil", category = "comedy"),
                movie(2, "Alien"),
                movie(3, "Ronin"),
                movie(4, "Heat"),
            ),
        )
    }

    /** The rail with Movies open, its Highlight on Movies in the rail. */
    private fun setMovies() {
        library()
        compose.setTvContent {
            MainShell(
                tabs = visibleTabs(liveEnabled = true, moviesEnabled = true, seriesEnabled = true),
                current = Tab.MOVIES,
                onSelect = {},
                onOpenSearch = {},
                onOpenSettings = {},
                onOpenProfiles = {},
                activeProfileName = "Sam",
                onExit = {},
                modifier = Modifier.fillMaxSize(),
            ) { _, _, _ ->
                view = LocalView.current
                MoviesScreen(
                    onOpenMovie = {},
                    onResume = {},
                    providers = ProvidersCarry.NO_PROVIDER,
                    isSyncing = false,
                    viewModel = viewModel,
                )
            }
        }
        compose.onAllNodes(hasText("Heat")).onFirst().assertExists()
        // Recommended is worked out in the background and lands above Recently added: wait for its
        // covers, not its title, which its placeholder shows from the start, so the rows don't move
        // under the Highlight partway through a test.
        awaitRecommended { !viewModel.recommendingMovies.value && viewModel.recommendedMovies.value.isNotEmpty() }
        compose.onAllNodes(cover(firstTitle)).onFirst().assertExists()
        compose.waitForIdle()
    }

    /** Recommended, once worked out in the background and handed back on the main thread, is [done]. */
    private fun awaitRecommended(done: () -> Boolean) {
        compose.waitForIdle()
        assertThat(done()).isTrue()
    }

    /** The title on the first cover of the first row, Recommended's. */
    private val firstTitle get() = viewModel.recommendedMovies.value.first().displayTitle

    /** The title on the second cover of the first row. */
    private val secondTitle get() = viewModel.recommendedMovies.value[1].displayTitle

    /** A cover, rather than the backdrop's heading of the same name. */
    private fun cover(title: String) = hasText(title) and hasClickAction()

    /** [title]'s cover in the first row it's in: a title can be in more than one row. */
    private fun firstCover(title: String) = compose.onAllNodes(cover(title))[0]

    private fun press(key: Key, times: Int = 1) = repeat(times) {
        compose.onRoot().performKeyInput { pressKey(key) }
        compose.waitForIdle()
    }

    private fun pressOkOnMoviesInTheRail() {
        compose.onNode(hasContentDescription("Movies") or hasText("Movies")).requestFocus()
        press(Key.DirectionCenter)
        // The rail hands the Highlight over after a moment, once the tab has had a chance to load.
        compose.mainClock.advanceTimeBy(500)
        compose.waitForIdle()
    }

    @Test
    fun `OK on Movies in the rail lands on the first cover of the first row`() {
        setMovies()
        pressOkOnMoviesInTheRail()

        firstCover(firstTitle).assertIsFocused()
        assertBackdrop(hasText(synopsisOf(firstTitle)))
        captureScreen("movies_first_highlight")
    }

    @Test
    fun `Left from the leftmost cover opens the sidebar on For you`() {
        setMovies()
        pressOkOnMoviesInTheRail()

        press(Key.DirectionLeft)

        compose.onNodeWithText("For you").assertIsFocused()
        compose.onNodeWithText("Action").assertExists()
        compose.onNodeWithText("Comedy").assertExists()
        captureScreen("movies_sidebar")
    }

    @Test
    fun `two providers' categories with the same id are both listed`() {
        runBlocking {
            graph.database.categories().upsertAll(listOf(Category("action", 2, "Action too", StreamKind.MOVIE, 2)))
        }
        setMovies()
        pressOkOnMoviesInTheRail()

        press(Key.DirectionLeft)

        compose.onNodeWithText("Action").assertExists()
        compose.onNodeWithText("Action too").assertExists()
    }

    @Test
    fun `Left from any other cover moves along the row, with the sidebar still tucked away`() {
        setMovies()
        pressOkOnMoviesInTheRail()
        press(Key.DirectionRight)

        firstCover(secondTitle).assertIsFocused()
        press(Key.DirectionLeft)

        firstCover(firstTitle).assertIsFocused()
        compose.onNodeWithText("For you").assertDoesNotExist()
    }

    /** Where the Highlight is: the bounds of the one focused node, which tell rows and cards apart. */
    private fun highlight() = compose.onNode(isFocused()).getUnclippedBoundsInRoot()

    @Test
    fun `Right from the sidebar goes back to the row and cover the viewer left, and closes it`() {
        setMovies()
        pressOkOnMoviesInTheRail()
        press(Key.DirectionDown)
        val left = highlight()

        press(Key.DirectionLeft)
        compose.onNodeWithText("For you").assertIsFocused()
        press(Key.DirectionRight)

        assertThat(highlight()).isEqualTo(left)
        compose.onNodeWithText("For you").assertDoesNotExist()
    }

    @Test
    fun `Down moves the next row to the top, with nothing of the row above left showing under the backdrop`() {
        // As on a Fire TV, where Compose otherwise keeps a highlighted item a third of the way down.
        shadowOf(compose.activity.packageManager).setSystemFeature(PackageManager.FEATURE_LEANBACK, true)
        setMovies()
        pressOkOnMoviesInTheRail()

        press(Key.DirectionDown)
        compose.mainClock.advanceTimeBy(1_000)
        compose.waitForIdle()

        val rowTop = compose.onNodeWithText(compose.activity.getString(R.string.vod_recently_added))
            .fetchSemanticsNode().boundsInRoot.top
        val showing = compose.onAllNodes(hasClickAction() and hasText("", substring = true)).fetchSemanticsNodes()
            .map { it.boundsInRoot }.filter { it.height > 0f && it.width > 0f }
        assertThat(showing.map { it.top }.filter { it < rowTop }).isEmpty()
    }

    @Test
    fun `picking a category keeps the Highlight on it, and Right moves into its grid`() {
        setMovies()
        pressOkOnMoviesInTheRail()
        press(Key.DirectionLeft)
        press(Key.DirectionDown)

        press(Key.DirectionCenter)
        compose.onAllNodes(hasText("Brazil")).assertCountEquals(0)
        compose.onAllNodes(cover("Heat")).onFirst().assertExists()
        compose.onNodeWithText("Action").assertIsFocused()
        press(Key.DirectionRight)
        compose.onNodeWithText("For you").assertDoesNotExist()
        // Action's films, newest first, and nothing from Comedy.
        compose.onNode(cover("Heat")).assertIsFocused()
        compose.onNode(cover("Ronin")).assertExists()
        compose.onNode(cover("Alien")).assertExists()
        captureScreen("movies_category_grid")
    }

    @Test
    fun `a category's covers are the same size as For you's`() {
        setMovies()
        pressOkOnMoviesInTheRail()
        val forYou = firstCover(firstTitle).getUnclippedBoundsInRoot()
        press(Key.DirectionLeft)
        press(Key.DirectionDown)

        press(Key.DirectionCenter)
        compose.onAllNodes(hasText("Brazil")).assertCountEquals(0)
        compose.onAllNodes(cover("Heat")).onFirst().assertExists()

        val grid = compose.onNode(cover("Heat")).getUnclippedBoundsInRoot()
        assertThat((grid.right - grid.left).value).isWithin(0.5f).of((forYou.right - forYou.left).value)
        assertThat((grid.bottom - grid.top).value).isWithin(0.5f).of((forYou.bottom - forYou.top).value)
    }

    private fun synopsisOf(title: String) = "What happens in $title."

    /** The backdrop says [what], once the Highlight has rested long enough for it to follow. */
    private fun assertBackdrop(what: SemanticsMatcher) {
        compose.mainClock.advanceTimeBy(BACKDROP_REST_MILLIS)
        compose.onAllNodes(what).onFirst().assertExists()
    }

    @Test
    fun `the backdrop changes only once the Highlight has rested on a title`() {
        setMovies()
        pressOkOnMoviesInTheRail()
        val first = firstTitle
        val second = secondTitle
        assertBackdrop(hasText(synopsisOf(first)))

        compose.mainClock.autoAdvance = false
        press(Key.DirectionRight)
        compose.mainClock.advanceTimeBy(300)
        compose.waitForIdle()
        compose.onNodeWithText(synopsisOf(first)).assertExists()
        compose.onNodeWithText(synopsisOf(second)).assertDoesNotExist()

        compose.mainClock.advanceTimeBy(200)
        // The paused clock has to be stepped for the screen to draw what the database sent back: the
        // old synopsis fades out before the new one shows.
        compose.waitForIdle()
        compose.mainClock.advanceTimeBy(FADE_MILLIS)
        compose.waitForIdle()
        compose.onAllNodesWithText(synopsisOf(second)).onFirst().assertExists()
        compose.onNodeWithText(synopsisOf(first)).assertDoesNotExist()
    }

    @Test
    fun `a film in Continue Watching shows its backdrop when highlighted, as covers do`() {
        library()
        runBlocking {
            graph.viewerLibrary.recordProgress("movie:4", positionMillis = 600_000, durationMillis = 6_000_000)
        }
        setMovies()
        pressOkOnMoviesInTheRail()

        compose.onNode(isFocused() and hasText("Heat", substring = true)).assertExists()
        assertBackdrop(hasText(synopsisOf("Heat")))
        captureScreen("movies_continue_watching_highlight")
    }

    @Test
    fun `the synopsis starts in the same place whether or not the title has a status`() {
        library()
        runBlocking {
            graph.viewerLibrary.recordProgress("movie:4", positionMillis = 600_000, durationMillis = 6_000_000)
        }
        setMovies()
        pressOkOnMoviesInTheRail()
        assertBackdrop(hasText(synopsisOf("Heat")))
        val withStatus = compose.onNodeWithText(synopsisOf("Heat")).getUnclippedBoundsInRoot().top

        press(Key.DirectionDown)
        assertBackdrop(hasText(synopsisOf(firstTitle)))
        val without = compose.onNodeWithText(synopsisOf(firstTitle)).getUnclippedBoundsInRoot().top

        assertThat(without).isEqualTo(withStatus)
    }

    @Test
    fun `a show in Continue Watching shows its backdrop when highlighted, as covers do`() {
        runBlocking {
            graph.database.categories().upsertAll(listOf(Category("drama", 1, "Drama", StreamKind.SERIES)))
            graph.database.series().upsertAll(
                listOf(
                    Series(1, 1, "s", "Slow Horses", "drama", null, null, null, "Spies.", addedMillis = 2),
                    Series(2, 1, "2", "Severance", "drama", null, null, null, "Work.", addedMillis = 1),
                ),
            )
            graph.database.episodes().upsertAll(listOf(testEpisode(2, 4)))
            graph.viewerLibrary.recordProgress("ep:24", positionMillis = 600_000, durationMillis = 2_400_000)
        }
        compose.setTvContent {
            MainShell(
                tabs = visibleTabs(liveEnabled = true, moviesEnabled = true, seriesEnabled = true),
                current = Tab.SHOWS,
                onSelect = {},
                onOpenSearch = {},
                onOpenSettings = {},
                onOpenProfiles = {},
                activeProfileName = "Sam",
                onExit = {},
                modifier = Modifier.fillMaxSize(),
            ) { _, _, _ ->
                SeriesScreen(onOpenSeries = {
                }, onResume = {}, providers = ProvidersCarry.NO_PROVIDER, isSyncing = false, viewModel = viewModel)
            }
        }
        awaitRecommended { !viewModel.recommendingSeries.value && viewModel.recommendedSeries.value.isNotEmpty() }
        compose.onNode(hasContentDescription("Shows") or hasText("Shows")).requestFocus()
        press(Key.DirectionCenter)
        compose.mainClock.advanceTimeBy(500)
        compose.waitForIdle()

        compose.onNode(isFocused() and hasText("Slow Horses", substring = true)).assertExists()
        assertBackdrop(hasText("Spies."))
    }

    @Test
    fun `scrolling a grid fetches covers about four rows ahead, at the size they're drawn`() {
        library()
        runBlocking {
            graph.database.movies().upsertAll((100L..399L).map { movie(it, "Film $it", category = "big", added = 0) })
        }
        runBlocking { graph.database.categories().upsertAll(listOf(Category("big", 1, "Big", StreamKind.MOVIE, 2))) }
        setMovies()
        pressOkOnMoviesInTheRail()
        press(Key.DirectionLeft)
        compose.onAllNodes(hasText("Big")).onFirst().assertExists()
        compose.onNodeWithText("Big").performClick()
        compose.onAllNodes(cover("Film 100")).onFirst().assertExists()
        press(Key.DirectionRight)

        press(Key.DirectionDown, times = 6)
        compose.waitForIdle()

        // Undated, so the grid runs by name, Film 100, 101, ...: the highest number shown is furthest down.
        val covers = compose.onAllNodes(hasClickAction() and hasText("Film ", substring = true)).fetchSemanticsNodes()
        val columns = covers.map { it.boundsInRoot.top }.let { tops -> tops.count { it == tops.min() } }
        val lastShown = covers.maxOf { it.config[SemanticsProperties.Text].first().text.removePrefix("Film ").toInt() }
        fun rowsOn(rows: Int) = "https://example.com/${lastShown + rows * columns}.jpg"

        compose.waitForIdle()
        assertThat(artwork.requests.any { it.first == rowsOn(4) }).isTrue()
        assertThat(artwork.requests.map { it.first }).doesNotContain(rowsOn(8))
        // The Stick's 1080p at xhdpi: a 118 x 177 dp cover is 236 x 354 pixels.
        assertThat(artwork.requests.first { it.first == rowsOn(4) }.second).isEqualTo(Size(236, 354))
    }

    @Test
    fun `Up from For you reaches Find a category, OK opens the keyboard, typing narrows, and Down returns`() {
        setMovies()
        pressOkOnMoviesInTheRail()
        press(Key.DirectionLeft)

        device.remote(KeyEvent.KEYCODE_DPAD_UP)
        assertThat(device.highlighted()).isTrue()
        assertThat(device.showing).isFalse()
        device.remote(KeyEvent.KEYCODE_DPAD_CENTER)
        assertThat(device.showing).isTrue()

        device.type("com")
        compose.onNodeWithText("Comedy").assertExists()
        compose.onNodeWithText("Action").assertDoesNotExist()
        compose.onNodeWithText("1 of 2").assertExists()
        captureScreen("movies_find_category")

        device.close()
        device.remote(KeyEvent.KEYCODE_DPAD_DOWN)
        compose.onNodeWithText("For you").assertIsFocused()
    }

    @Test
    fun `Left from the sidebar goes to the rail, and the sidebar tucks away`() {
        setMovies()
        pressOkOnMoviesInTheRail()
        press(Key.DirectionLeft)
        compose.onNodeWithText("For you").assertIsFocused()

        press(Key.DirectionLeft)

        compose.onNode(hasContentDescription("Movies") or hasText("Movies")).assertIsFocused()
        compose.onNodeWithText("For you").assertDoesNotExist()
    }

    @Test
    fun `OK on Shows in the rail lands on its first cover, and Left opens its sidebar`() {
        runBlocking {
            graph.database.categories().upsertAll(listOf(Category("drama", 1, "Drama", StreamKind.SERIES)))
            graph.database.series().upsertAll(
                listOf(
                    Series(1, 1, "1", "Slow Horses", "drama", null, null, null, "Spies.", addedMillis = 2),
                    Series(2, 1, "2", "Severance", "drama", null, null, null, "Work.", addedMillis = 1),
                ),
            )
        }
        compose.setTvContent {
            MainShell(
                tabs = visibleTabs(liveEnabled = true, moviesEnabled = true, seriesEnabled = true),
                current = Tab.SHOWS,
                onSelect = {},
                onOpenSearch = {},
                onOpenSettings = {},
                onOpenProfiles = {},
                activeProfileName = "Sam",
                onExit = {},
                modifier = Modifier.fillMaxSize(),
            ) { _, _, _ ->
                SeriesScreen(onOpenSeries = {
                }, onResume = {}, providers = ProvidersCarry.NO_PROVIDER, isSyncing = false, viewModel = viewModel)
            }
        }
        compose.onAllNodes(cover("Slow Horses")).onFirst().assertExists()
        // Recommended's covers, not its title, which its placeholder shows from the start.
        awaitRecommended { !viewModel.recommendingSeries.value && viewModel.recommendedSeries.value.isNotEmpty() }
        compose.onAllNodes(cover(viewModel.recommendedSeries.value.first().displayTitle)).onFirst().assertExists()
        compose.waitForIdle()

        compose.onNode(hasContentDescription("Shows") or hasText("Shows")).requestFocus()
        press(Key.DirectionCenter)
        compose.mainClock.advanceTimeBy(500)
        compose.waitForIdle()

        firstCover(viewModel.recommendedSeries.value.first().displayTitle).assertIsFocused()
        assertBackdrop(hasText("Spies.") or hasText("Work."))
        captureScreen("shows_first_highlight")

        press(Key.DirectionLeft)
        compose.onNodeWithText("For you").assertIsFocused()
        compose.onNodeWithText("Drama").assertExists()
    }

    private fun <T> List<T>.dedupeRuns() = filterIndexed { i, value -> i == 0 || value != this[i - 1] }

    @Test
    fun `the sidebar slides open and shut, and the screen beside it changes width only once each way`() {
        setMovies()
        pressOkOnMoviesInTheRail()
        compose.mainClock.autoAdvance = false
        val widths = mutableListOf<Float>()
        val sidebarLefts = mutableListOf<Float>()
        fun frames() = repeat(40) {
            compose.mainClock.advanceTimeByFrame()
            widths +=
                compose.onNodeWithTag("browse-content").getUnclippedBoundsInRoot().let { (it.right - it.left).value }
            // Unclipped: the screen clips the sidebar while it's partly off its left edge.
            if (compose.onAllNodesWithText("For you").fetchSemanticsNodes().isNotEmpty()) {
                sidebarLefts += compose.onNodeWithText("For you").getUnclippedBoundsInRoot().left.value
            }
        }

        press(Key.DirectionLeft)
        frames()
        compose.onNodeWithText("For you").assertIsFocused()
        // In from off its left edge, through the frames in between, rather than appearing at once.
        assertThat(sidebarLefts.distinct().size).isAtLeast(3)
        assertThat(sidebarLefts.first()).isLessThan(sidebarLefts.last())

        press(Key.DirectionRight)
        frames()
        compose.onNodeWithText("For you").assertDoesNotExist()

        val closed = widths.first()
        assertThat(widths.dedupeRuns()).containsExactly(closed, closed - 214f, closed).inOrder()
    }
}

/** How long the backdrop waits for the Highlight to rest before following it (RestingBackdrop). */
private const val BACKDROP_REST_MILLIS = 400L

/** The backdrop's fade out of the old title (ART_FADE_MILLIS), and a few frames to draw the new one. */
private const val FADE_MILLIS = 300L
