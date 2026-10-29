/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv

import android.content.Context
import android.os.Looper
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.drm.DrmSessionManagerProvider
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.upstream.LoadErrorHandlingPolicy
import androidx.media3.test.utils.ExoPlayerTestRunner
import androidx.media3.test.utils.FakeClock
import androidx.media3.test.utils.FakeMediaSource
import androidx.media3.test.utils.FakeTimeline
import androidx.media3.test.utils.TestExoPlayerBuilder
import androidx.media3.test.utils.robolectric.TestPlayerRunHelper
import app.opentv.data.repo.MediaKey
import app.opentv.data.repo.Playable
import app.opentv.player.PlayerController
import java.io.IOException
import java.time.Duration
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import okhttp3.OkHttpClient
import org.robolectric.Shadows.shadowOf

/*
 * A real ExoPlayer for the player tests (#68), playing fake media instead of a stream. Its clock only
 * moves when a test moves it, so a "playing" film stays where the test put it and a position read
 * after a skip is exact.
 */

/** A one-hour film: seekable, with a fixed length. */
@UnstableApi
val FILM = FakeTimeline.TimelineWindowDefinition(
    /* isSeekable= */
    true,
    /* isDynamic= */
    false,
    /* durationUs= */
    60L * 60 * C.MICROS_PER_SECOND,
)

/** A recording still being written: ExoPlayer can't seek it, so the player skips within what's buffered. */
@UnstableApi
val RECORDING = FakeTimeline.TimelineWindowDefinition(
    /* isSeekable= */
    false,
    /* isDynamic= */
    true,
    /* durationUs= */
    10L * 60 * C.MICROS_PER_SECOND,
)

/**
 * A live channel with live pause's 2 minutes behind the edge to seek into, playing near the edge.
 * ExoPlayer counts a window as live only when its media item carries a live configuration.
 */
@UnstableApi
val LIVE = FakeTimeline.TimelineWindowDefinition(
    /* periodCount= */
    1,
    /* id= */
    "live",
    /* isSeekable= */
    true,
    /* isDynamic= */
    true,
    /* isLive= */
    true,
    /* isPlaceholder= */
    false,
    /* durationUs= */
    120L * C.MICROS_PER_SECOND,
    /* defaultPositionUs= */
    110L * C.MICROS_PER_SECOND,
    /* windowOffsetInFirstPeriodUs= */
    0L,
    listOf(androidx.media3.common.AdPlaybackState.NONE),
    MediaItem.Builder()
        .setUri("https://example.com/live")
        .setLiveConfiguration(MediaItem.LiveConfiguration.Builder().build())
        .build(),
)

@UnstableApi
class TestPlayers(private val context: Context) {

    // Not auto-advancing: time moves only when a test moves it.
    val clock = FakeClock(false)
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    /** Every stream the controllers were handed, in order. */
    val played = mutableListOf<String>()

    /**
     * A [PlayerController] whose every stream plays [window], resolved by [resolve] (by default a
     * stream at a placeholder address). The first [failing] streams fail as a dropped connection does.
     */
    fun controller(
        window: FakeTimeline.TimelineWindowDefinition = FILM,
        dvr: Boolean = false,
        failing: Int = 0,
        resolve: suspend (MediaKey) -> Playable = ::placeholder,
    ) = PlayerController(
        context,
        scope,
        OkHttpClient(),
        resolve = resolve,
        dvr = dvr,
        buildPlayer = {
            TestExoPlayerBuilder(context)
                .setClock(clock)
                .setMediaSourceFactory(FakeSources(window, played, failing))
                .build()
        },
    )

    /** Cancels what the controllers left running, such as an automatic restart, so it can't outlive the test. */
    fun close() = scope.cancel()

    /** Starts [controller] on a stream and lets it buffer until it's playing. */
    fun play(controller: PlayerController, isLive: Boolean = false) {
        controller.play(PlayerController.Request(if (isLive) MediaKey.Live(1) else MediaKey.Movie(1)), debounce = false)
        untilReady(controller.player)
    }

    /**
     * Runs the main thread, where the controller's zap delay and restarts wait, until [done]. Moves its
     * clock on in small steps, letting a portal call on another thread come back in between.
     */
    fun until(done: () -> Boolean) {
        repeat(MAX_TICKS) {
            if (done()) return
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(TICK_MILLIS * 10))
            clock.advanceTime(TICK_MILLIS)
            Thread.sleep(1)
        }
        error("Gave up waiting on the controller")
    }

    fun untilReady(player: ExoPlayer) {
        repeat(MAX_TICKS) {
            if (player.playbackState == Player.STATE_READY) return
            clock.advanceTime(TICK_MILLIS)
            TestPlayerRunHelper.run(player).untilPendingCommandsAreFullyHandled()
        }
        error("The player never got ready: state ${player.playbackState}")
    }

    private class FakeSources(
        private val window: FakeTimeline.TimelineWindowDefinition,
        private val played: MutableList<String>,
        private var failing: Int,
    ) : MediaSource.Factory {
        override fun createMediaSource(mediaItem: MediaItem): MediaSource {
            played += mediaItem.localConfiguration?.uri.toString()
            if (failing-- > 0) return DroppedSource()
            return FakeMediaSource(FakeTimeline(window), ExoPlayerTestRunner.VIDEO_FORMAT)
        }

        override fun setDrmSessionManagerProvider(provider: DrmSessionManagerProvider) = this

        override fun setLoadErrorHandlingPolicy(policy: LoadErrorHandlingPolicy) = this

        override fun getSupportedTypes(): IntArray = intArrayOf(C.CONTENT_TYPE_OTHER)
    }

    /** A stream whose connection drops before it ever starts. */
    private class DroppedSource : FakeMediaSource(null) {
        override fun maybeThrowSourceInfoRefreshError() = throw IOException("Connection dropped")
    }

    private companion object {
        fun placeholder(key: MediaKey) = Playable.Ready(
            key,
            "https://example.com/stream",
            userAgent = "",
            title = "Film",
            isLive = key is MediaKey.Live,
        )

        const val TICK_MILLIS = 10L
        const val MAX_TICKS = 500
    }
}
