/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv

import app.opentv.data.model.Category
import app.opentv.data.model.Movie
import app.opentv.data.model.PlaybackPosition
import app.opentv.data.model.StreamKind
import app.opentv.data.repo.Profiles
import app.opentv.data.repo.categoryGroupKey
import app.opentv.ui.ProfilesViewModel
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The picture behind each Profile on Who's watching?: what they last played, never anything locked. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class ProfileStillsTest {

    @get:Rule
    val testGraph = TestGraph(name = "profile-stills-test")

    private val app = testGraph.app
    private val graph = testGraph.graph
    private val films = Category("films", 1, "Films", StreamKind.MOVIE)
    private val adult = Category("adult", 1, "Adult", StreamKind.MOVIE)

    // The settings file outlives each test.
    @After
    fun close() {
        graph.settings.setHiddenCategories(emptySet())
    }

    private fun film(id: Long, category: Category) = Movie(
        id = id,
        sourceId = 1,
        streamId = "$id",
        name = "Film $id",
        categoryId = category.id,
        posterUrl = "https://example.com/$id-poster.jpg",
        backdropUrl = "https://example.com/$id-backdrop.jpg",
        rating = null,
        year = null,
        plot = null,
        durationSeconds = null,
        containerExtension = "mp4",
        streamUrl = "",
    )

    private suspend fun played(profileId: Long, movieId: Long, at: Long) = graph.viewerLibrary.mergeSynced(
        PlaybackPosition(
            profileId,
            "movie:$movieId",
            positionMillis = 60_000,
            durationMillis = 600_000,
            updatedAtMillis = at,
        ),
    )

    /** The view model's stills, drawn throughout, once its work has settled, if [until] holds. */
    private fun ProfilesViewModel.stillsWhen(until: (Map<Long, String>) -> Boolean): Map<Long, String> {
        testGraph.launch { stills.collect {} }
        return testGraph.firstWhenIdle(stills, until)
    }

    @Test
    fun `each Profile shows a frame from what they last played, and one with nothing shows none`() = runBlocking<Unit> {
        graph.database.categories().upsertAll(listOf(films))
        graph.database.movies().upsertAll(listOf(film(1, films), film(2, films)))
        val kids = checkNotNull(graph.profiles.create("Kids"))
        played(Profiles.BASE_PROFILE_ID, movieId = 1, at = 1_000)
        played(Profiles.BASE_PROFILE_ID, movieId = 2, at = 2_000)

        val stills = testGraph.viewModel { ProfilesViewModel(app, graph) }.stillsWhen { it.isNotEmpty() }

        assertThat(stills).containsExactly(Profiles.BASE_PROFILE_ID, "https://example.com/2-backdrop.jpg")
        assertThat(stills).doesNotContainKey(kids)
    }

    @Test
    fun `a title in a Parental-Locked category is passed over for the one played before it`() = runBlocking<Unit> {
        graph.database.categories().upsertAll(listOf(films, adult))
        graph.database.movies().upsertAll(listOf(film(1, films), film(2, adult)))
        graph.settings.setHiddenCategories(setOf(categoryGroupKey(adult)))
        played(Profiles.BASE_PROFILE_ID, movieId = 1, at = 1_000)
        played(Profiles.BASE_PROFILE_ID, movieId = 2, at = 2_000)

        val stills = testGraph.viewModel { ProfilesViewModel(app, graph) }.stillsWhen { it.isNotEmpty() }

        assertThat(stills).containsExactly(Profiles.BASE_PROFILE_ID, "https://example.com/1-backdrop.jpg")
    }
}
