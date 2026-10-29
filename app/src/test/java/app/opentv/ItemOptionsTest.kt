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
        assertThat(OptionsFor.Episode(WatchStatus.Watched).options()).containsExactly(ItemOption.MarkUnwatched)
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
}
