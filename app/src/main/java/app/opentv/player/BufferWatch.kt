/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv.player

import androidx.annotation.OptIn
import androidx.annotation.VisibleForTesting
import androidx.media3.common.Timeline
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.LoadControl
import androidx.media3.exoplayer.Renderer
import androidx.media3.exoplayer.analytics.PlayerId
import androidx.media3.exoplayer.source.MediaSource.MediaPeriodId
import androidx.media3.exoplayer.source.TrackGroupArray
import androidx.media3.exoplayer.trackselection.ExoTrackSelection
import androidx.media3.exoplayer.upstream.Allocator
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Passes everything to [delegate], and says whether the player has stopped loading because its
 * buffer is full. Live pause ends on that rather than on a clock: under the memory limit, how long
 * a pause lasts depends on the stream's bitrate, which IPTV streams often don't report (#182).
 *
 * Every method is forwarded by hand. `LoadControl by delegate` looks the same, but Kotlin doesn't
 * forward a Java interface's default methods, and nearly all of LoadControl's are defaults: the
 * player would get the interface's own answers, which drop the rewind window or throw.
 */
@OptIn(UnstableApi::class)
@Suppress("DEPRECATION", "OVERRIDE_DEPRECATION", "TooManyFunctions")
internal class BufferWatch(private val delegate: LoadControl) : LoadControl {

    private val _full = MutableStateFlow(false)

    /** Asked on the playback thread, and again every second while paused, so it stays current. */
    val full: StateFlow<Boolean> = _full.asStateFlow()

    override fun shouldContinueLoading(parameters: LoadControl.Parameters): Boolean =
        delegate.shouldContinueLoading(parameters).also { update(full = !it) }

    /** Media3 stands in for this in the app; the screen tests call it to fill the buffer. */
    @VisibleForTesting
    fun update(full: Boolean) {
        _full.value = full
    }

    override fun getAllocator(): Allocator = delegate.allocator

    override fun onPrepared(playerId: PlayerId) = delegate.onPrepared(playerId)

    override fun onPrepared() = delegate.onPrepared()

    override fun onTracksSelected(
        parameters: LoadControl.Parameters,
        trackGroups: TrackGroupArray,
        trackSelections: Array<out ExoTrackSelection?>,
    ) = delegate.onTracksSelected(parameters, trackGroups, trackSelections)

    override fun onTracksSelected(
        playerId: PlayerId,
        timeline: Timeline,
        mediaPeriodId: MediaPeriodId,
        renderers: Array<out Renderer>,
        trackGroups: TrackGroupArray,
        trackSelections: Array<out ExoTrackSelection?>,
    ) = delegate.onTracksSelected(playerId, timeline, mediaPeriodId, renderers, trackGroups, trackSelections)

    override fun onTracksSelected(
        timeline: Timeline,
        mediaPeriodId: MediaPeriodId,
        renderers: Array<out Renderer>,
        trackGroups: TrackGroupArray,
        trackSelections: Array<out ExoTrackSelection?>,
    ) = delegate.onTracksSelected(timeline, mediaPeriodId, renderers, trackGroups, trackSelections)

    override fun onTracksSelected(
        renderers: Array<out Renderer>,
        trackGroups: TrackGroupArray,
        trackSelections: Array<out ExoTrackSelection?>,
    ) = delegate.onTracksSelected(renderers, trackGroups, trackSelections)

    override fun onStopped(playerId: PlayerId) = delegate.onStopped(playerId)

    override fun onStopped() = delegate.onStopped()

    override fun onReleased(playerId: PlayerId) = delegate.onReleased(playerId)

    override fun onReleased() = delegate.onReleased()

    override fun getBackBufferDurationUs(playerId: PlayerId): Long = delegate.getBackBufferDurationUs(playerId)

    override fun getBackBufferDurationUs(): Long = delegate.backBufferDurationUs

    override fun retainBackBufferFromKeyframe(playerId: PlayerId): Boolean =
        delegate.retainBackBufferFromKeyframe(playerId)

    override fun retainBackBufferFromKeyframe(): Boolean = delegate.retainBackBufferFromKeyframe()

    override fun shouldContinueLoading(
        playbackPositionUs: Long,
        bufferedDurationUs: Long,
        playbackSpeed: Float,
    ): Boolean = delegate.shouldContinueLoading(playbackPositionUs, bufferedDurationUs, playbackSpeed)

    override fun shouldContinuePreloading(timeline: Timeline, mediaPeriodId: MediaPeriodId, bufferedDurationUs: Long) =
        delegate.shouldContinuePreloading(timeline, mediaPeriodId, bufferedDurationUs)

    override fun shouldStartPlayback(parameters: LoadControl.Parameters): Boolean =
        delegate.shouldStartPlayback(parameters)

    override fun shouldStartPlayback(
        timeline: Timeline,
        mediaPeriodId: MediaPeriodId,
        bufferedDurationUs: Long,
        playbackSpeed: Float,
        rebuffering: Boolean,
        targetLiveOffsetUs: Long,
    ): Boolean = delegate.shouldStartPlayback(
        timeline,
        mediaPeriodId,
        bufferedDurationUs,
        playbackSpeed,
        rebuffering,
        targetLiveOffsetUs,
    )

    override fun shouldStartPlayback(
        bufferedDurationUs: Long,
        playbackSpeed: Float,
        rebuffering: Boolean,
        targetLiveOffsetUs: Long,
    ): Boolean = delegate.shouldStartPlayback(bufferedDurationUs, playbackSpeed, rebuffering, targetLiveOffsetUs)
}
