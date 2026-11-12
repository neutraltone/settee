/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv

import app.opentv.data.model.Programme
import app.opentv.data.model.RecordingStatus
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
    fun `an In Progress episode with an earlier one not Watched offers to play from the start as well`() {
        assertThat(OptionsFor.Episode(inProgress, earlierToMark = true).options()).containsExactly(
            ItemOption.PlayFromStart,
            ItemOption.MarkWatched,
            ItemOption.MarkWatchedUpToHere,
            ItemOption.MarkUnwatched,
        ).inOrder()
    }

    @Test
    fun `an episode In Progress can play from the start, but offers no Show info`() {
        assertThat(OptionsFor.Episode(inProgress, earlierToMark = false).options())
            .containsExactly(ItemOption.PlayFromStart, ItemOption.MarkWatched, ItemOption.MarkUnwatched).inOrder()
    }

    @Test
    fun `a series can be looked up and made a Favourite, but has nothing of its own to play from the start`() {
        assertThat(OptionsFor.Series(favourite = false).options())
            .containsExactly(ItemOption.ShowInfo, ItemOption.AddFavourite).inOrder()
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
    fun `the live player offers the pickers, sleep timer, Favourite and Record, in that order`() {
        assertThat(
            OptionsFor.LivePlayer(hasQualities = true, favourite = false, recording = false).options(),
        ).containsExactly(
            ItemOption.Subtitles,
            ItemOption.Audio,
            ItemOption.Quality,
            ItemOption.AspectRatio,
            ItemOption.SleepTimer,
            ItemOption.AddFavourite,
            ItemOption.RecordNow,
        ).inOrder()
    }

    @Test
    fun `the live player offers Quality only when the Channel has more than one`() {
        assertThat(OptionsFor.LivePlayer(hasQualities = false, favourite = false, recording = false).options())
            .doesNotContain(ItemOption.Quality)
    }

    @Test
    fun `the live player's Favourite and Record follow the Channel's state`() {
        val options = OptionsFor.LivePlayer(hasQualities = false, favourite = true, recording = true).options()

        assertThat(options).containsAtLeast(ItemOption.RemoveFavourite, ItemOption.StopRecording)
        assertThat(options).containsNoneOf(ItemOption.AddFavourite, ItemOption.RecordNow)
    }

    @Test
    fun `the movie and episode player offers Mark watched last, only when it can be marked`() {
        assertThat(OptionsFor.VodPlayer(canMarkWatched = true).options()).containsExactly(
            ItemOption.Subtitles,
            ItemOption.Audio,
            ItemOption.SleepTimer,
            ItemOption.MarkWatched,
        ).inOrder()
        assertThat(OptionsFor.VodPlayer(canMarkWatched = false).options()).containsExactly(
            ItemOption.Subtitles,
            ItemOption.Audio,
            ItemOption.SleepTimer,
        ).inOrder()
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

    @Test
    fun `a recording in progress can be stopped or deleted`() {
        assertThat(OptionsFor.OfRecording(RecordingStatus.RECORDING).options())
            .containsExactly(ItemOption.StopRecording, ItemOption.DeleteRecording).inOrder()
    }

    @Test
    fun `a booking can only be cancelled, as it has nothing to delete yet`() {
        assertThat(OptionsFor.OfRecording(RecordingStatus.SCHEDULED).options())
            .containsExactly(ItemOption.CancelRecording)
    }

    @Test
    fun `a finished or failed recording can only be deleted, as OK already plays or retries it`() {
        assertThat(
            OptionsFor.OfRecording(RecordingStatus.COMPLETED).options(),
        ).containsExactly(ItemOption.DeleteRecording)
        assertThat(OptionsFor.OfRecording(RecordingStatus.FAILED).options()).containsExactly(ItemOption.DeleteRecording)
    }

    @Test
    fun `a Reminder can only be cancelled, and a series link only removed`() {
        assertThat(OptionsFor.OfReminder.options()).containsExactly(ItemOption.CancelReminder)
        assertThat(OptionsFor.OfSeriesLink.options()).containsExactly(ItemOption.RemoveSeriesLink)
    }
}
