/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv

import app.opentv.data.model.Programme
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
    fun `a channel with nothing in the guide offers the actions that need no programme`() {
        assertThat(channel().options()).containsExactly(
            ItemOption.Watch,
            ItemOption.AddFavourite,
            ItemOption.HideChannel,
            ItemOption.OpenInExternalPlayer,
            ItemOption.RecordNow,
        ).inOrder()
    }

    @Test
    fun `a Favourite channel offers removing it instead of adding it`() {
        val options = channel(favourite = true).options()

        assertThat(options).contains(ItemOption.RemoveFavourite)
        assertThat(options).doesNotContain(ItemOption.AddFavourite)
    }

    @Test
    fun `a channel recording offers stopping it instead of starting one`() {
        val options = channel(recording = true).options()

        assertThat(options).contains(ItemOption.StopRecording)
        assertThat(options).doesNotContain(ItemOption.RecordNow)
    }

    @Test
    fun `a programme on now offers recording its whole series, by name`() {
        assertThat(channel(onNow = NEWS).options()).contains(ItemOption.RecordWholeSeries(NEWS))
    }

    @Test
    fun `upcoming programmes offer scheduling one`() {
        assertThat(channel(hasUpcoming = true).options()).contains(ItemOption.ScheduleLater)
    }

    @Test
    fun `everything applying comes in the panel's order`() {
        assertThat(channel(favourite = true, recording = true, onNow = NEWS, hasUpcoming = true).options())
            .containsExactly(
                ItemOption.Watch,
                ItemOption.RemoveFavourite,
                ItemOption.HideChannel,
                ItemOption.OpenInExternalPlayer,
                ItemOption.StopRecording,
                ItemOption.RecordWholeSeries(NEWS),
                ItemOption.ScheduleLater,
            ).inOrder()
    }

    private fun channel(
        favourite: Boolean = false,
        recording: Boolean = false,
        onNow: Programme? = null,
        hasUpcoming: Boolean = false,
    ) = OptionsFor.Channel(favourite, recording, onNow, hasUpcoming)

    private companion object {
        val NEWS =
            Programme(feedId = 1, epgChannelId = "bbc1", startUtcMillis = 0, endUtcMillis = 1, title = "The News")
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
