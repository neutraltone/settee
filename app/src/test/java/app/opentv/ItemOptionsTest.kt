/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv

import app.opentv.data.repo.WatchStatus
import app.opentv.ui.options.ItemOption
import app.opentv.ui.options.OptionsFor
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class ItemOptionsTest {

    @Test
    fun `a movie never played can be marked Watched and made a Favourite`() {
        assertThat(OptionsFor.Movie(WatchStatus.Unwatched, favourite = false).options())
            .containsExactly(ItemOption.MarkWatched, ItemOption.AddFavourite).inOrder()
    }

    @Test
    fun `a Watched Favourite movie can be marked Unwatched and stop being a Favourite`() {
        assertThat(OptionsFor.Movie(WatchStatus.Watched, favourite = true).options())
            .containsExactly(ItemOption.MarkUnwatched, ItemOption.RemoveFavourite).inOrder()
    }

    @Test
    fun `a movie In Progress can be marked either way`() {
        assertThat(OptionsFor.Movie(WatchStatus.InProgress(0.5f, null), favourite = false).options())
            .containsExactly(ItemOption.MarkWatched, ItemOption.MarkUnwatched, ItemOption.AddFavourite).inOrder()
    }

    @Test
    fun `an episode offers only how it is marked, as episodes aren't Favourites`() {
        assertThat(OptionsFor.Episode(WatchStatus.Watched, earlierToMark = false).options())
            .containsExactly(ItemOption.MarkUnwatched)
    }

    @Test
    fun `an episode with an earlier one not Watched can be marked up to here`() {
        assertThat(OptionsFor.Episode(WatchStatus.Unwatched, earlierToMark = true).options())
            .containsExactly(ItemOption.MarkWatched, ItemOption.MarkWatchedUpToHere).inOrder()
    }

    @Test
    fun `a Watched episode with an earlier one not Watched can still be marked up to here`() {
        assertThat(OptionsFor.Episode(WatchStatus.Watched, earlierToMark = true).options())
            .containsExactly(ItemOption.MarkWatchedUpToHere, ItemOption.MarkUnwatched).inOrder()
    }

    @Test
    fun `an episode with every earlier one Watched doesn't repeat Mark watched as up to here`() {
        assertThat(OptionsFor.Episode(WatchStatus.Unwatched, earlierToMark = false).options())
            .containsExactly(ItemOption.MarkWatched)
    }

    @Test
    fun `a season all Unwatched can only be marked Watched`() {
        assertThat(OptionsFor.Season(listOf(WatchStatus.Unwatched, WatchStatus.Unwatched)).options())
            .containsExactly(ItemOption.MarkSeasonWatched)
    }

    @Test
    fun `a season all Watched can only be marked Unwatched`() {
        assertThat(OptionsFor.Season(listOf(WatchStatus.Watched, WatchStatus.Watched)).options())
            .containsExactly(ItemOption.MarkSeasonUnwatched)
    }

    @Test
    fun `a mixed season offers both marks`() {
        assertThat(OptionsFor.Season(listOf(WatchStatus.Watched, WatchStatus.Unwatched)).options())
            .containsExactly(ItemOption.MarkSeasonWatched, ItemOption.MarkSeasonUnwatched).inOrder()
    }

    @Test
    fun `a season of one In Progress episode offers both marks`() {
        assertThat(OptionsFor.Season(listOf(WatchStatus.InProgress(0.5f, null))).options())
            .containsExactly(ItemOption.MarkSeasonWatched, ItemOption.MarkSeasonUnwatched).inOrder()
    }

    @Test
    fun `a series offers whether it is a Favourite`() {
        assertThat(OptionsFor.Series(favourite = false).options()).containsExactly(ItemOption.AddFavourite)
    }

    @Test
    fun `a channel offers whether it is a Favourite`() {
        assertThat(OptionsFor.Channel(favourite = true).options()).containsExactly(ItemOption.RemoveFavourite)
    }

    @Test
    fun `a Continue Watching card can be marked Watched, removed, or its title made a Favourite`() {
        assertThat(OptionsFor.ContinueWatching(favourite = false).options()).containsExactly(
            ItemOption.MarkWatched,
            ItemOption.RemoveFromContinueWatching,
            ItemOption.AddFavourite,
        ).inOrder()
    }

    @Test
    fun `a Continue Watching card whose title can't be a Favourite offers no Favourite option`() {
        assertThat(OptionsFor.ContinueWatching(favourite = null).options())
            .containsExactly(ItemOption.MarkWatched, ItemOption.RemoveFromContinueWatching).inOrder()
    }

    @Test
    fun `a Recent Search can only be removed`() {
        assertThat(OptionsFor.RecentSearch.options()).containsExactly(ItemOption.RemoveRecentSearch)
    }
}
