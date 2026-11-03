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

    private val inProgress = WatchStatus.InProgress(0.5f, null)

    @Test
    fun `a movie never played can be looked up, marked Watched and made a Favourite`() {
        assertThat(OptionsFor.Movie(WatchStatus.Unwatched, favourite = false).options())
            .containsExactly(ItemOption.ShowInfo, ItemOption.MarkWatched, ItemOption.AddFavourite).inOrder()
    }

    @Test
    fun `a Watched Favourite movie can be looked up, marked Unwatched and stop being a Favourite`() {
        assertThat(OptionsFor.Movie(WatchStatus.Watched, favourite = true).options())
            .containsExactly(ItemOption.ShowInfo, ItemOption.MarkUnwatched, ItemOption.RemoveFavourite).inOrder()
    }

    @Test
    fun `a movie In Progress can play from the start, be looked up and be marked either way`() {
        assertThat(OptionsFor.Movie(inProgress, favourite = false).options()).containsExactly(
            ItemOption.PlayFromStart,
            ItemOption.ShowInfo,
            ItemOption.MarkWatched,
            ItemOption.MarkUnwatched,
            ItemOption.AddFavourite,
        ).inOrder()
    }

    @Test
    fun `an episode offers only how it is marked, as episodes aren't Favourites and it is on its series' page`() {
        assertThat(OptionsFor.Episode(WatchStatus.Watched).options()).containsExactly(ItemOption.MarkUnwatched)
    }

    @Test
    fun `an episode In Progress can play from the start, but offers no Show info`() {
        assertThat(OptionsFor.Episode(inProgress).options())
            .containsExactly(ItemOption.PlayFromStart, ItemOption.MarkWatched, ItemOption.MarkUnwatched).inOrder()
    }

    @Test
    fun `a series can be looked up and made a Favourite, but has nothing of its own to play from the start`() {
        assertThat(OptionsFor.Series(favourite = false).options())
            .containsExactly(ItemOption.ShowInfo, ItemOption.AddFavourite).inOrder()
    }

    @Test
    fun `a channel offers whether it is a Favourite`() {
        assertThat(OptionsFor.Channel(favourite = true).options()).containsExactly(ItemOption.RemoveFavourite)
    }

    @Test
    fun `a Continue Watching card In Progress offers both actions before the existing options`() {
        val card = OptionsFor.ContinueWatching(inProgress, inCatalogue = true, favourite = false)
        assertThat(card.options()).containsExactly(
            ItemOption.PlayFromStart,
            ItemOption.ShowInfo,
            ItemOption.MarkWatched,
            ItemOption.RemoveFromContinueWatching,
            ItemOption.AddFavourite,
        ).inOrder()
    }

    @Test
    fun `a Continue Watching card on an Up Next never played has nothing to play from the start`() {
        val upNext = OptionsFor.ContinueWatching(WatchStatus.Unwatched, inCatalogue = true, favourite = true)
        assertThat(upNext.options()).containsExactly(
            ItemOption.ShowInfo,
            ItemOption.MarkWatched,
            ItemOption.RemoveFromContinueWatching,
            ItemOption.RemoveFavourite,
        ).inOrder()
    }

    @Test
    fun `a Continue Watching card whose title has left the catalogue can't be played, shown or made a Favourite`() {
        val gone = OptionsFor.ContinueWatching(inProgress, inCatalogue = false, favourite = false)
        assertThat(gone.options()).containsExactly(
            ItemOption.MarkWatched,
            ItemOption.RemoveFromContinueWatching,
        ).inOrder()
    }

    @Test
    fun `a Recent Search can only be removed`() {
        assertThat(OptionsFor.RecentSearch.options()).containsExactly(ItemOption.RemoveRecentSearch)
    }
}
