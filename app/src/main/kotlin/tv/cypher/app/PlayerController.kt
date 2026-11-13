package tv.cypher.app

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.SurfaceView
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.upstream.DefaultAllocator
import tv.cypher.core.Channel
import tv.cypher.core.RebufferTracker
import tv.cypher.core.Stream
import tv.cypher.core.StreamFailover

/**
 * Plays one channel at a time with the headers each stream needs. A channel's streams are sorted best quality
 * first: it starts on the best (or the one it recently had to step down to), steps down one stream when
 * playback rebuffers, and moves on to the next stream on an error or a stall.
 */
@UnstableApi
class PlayerController(
    private val ctx: Context,
    private val surface: SurfaceView,
    private val listener: Listener,
) {
    interface Listener {
        /** [downgraded] is true when this is not the stream the channel started on in this tune. */
        fun onPlaying(channel: Channel, streamIndex: Int, downgraded: Boolean)
        fun onBuffering(buffering: Boolean)
        fun onFailed(channel: Channel)
        /** About to try a different stream of the channel (after buffering trouble or a failure). */
        fun onSwitching(channel: Channel, stream: Stream)
    }

    private val handler = Handler(Looper.getMainLooper())
    private var player: ExoPlayer? = null
    private var channel: Channel? = null
    private var failover: StreamFailover? = null
    /** When the current stream first reached READY (0 = not yet); decides retry-same-stream vs move-on. */
    private var readyAt = 0L
    private val stall = Runnable { fail() }
    private val rebuffers = RebufferTracker()
    private val stepDownLater = Runnable { stepDown() }
    private var firstIndex = -1
    private var startedIndex = -1

    val hasPlayer: Boolean get() = player != null

    /** [startIndex] is the stream to begin with (-1: the best one). */
    fun play(ch: Channel, startIndex: Int) {
        channel = ch
        failover = StreamFailover(ch.streams.size, startIndex)
        firstIndex = failover?.current ?: -1
        startedIndex = -1
        handler.removeCallbacks(stepDownLater)
        startCurrent()
    }

    fun stop() {
        handler.removeCallbacks(stall)
        handler.removeCallbacks(stepDownLater)
        player?.stop()
        player?.clearMediaItems()
    }

    fun release() {
        handler.removeCallbacks(stall)
        handler.removeCallbacks(stepDownLater)
        player?.release()
        player = null
    }

    private fun ensurePlayer(): ExoPlayer {
        player?.let { return it }
        val load = DefaultLoadControl.Builder()
            .setAllocator(DefaultAllocator(true, C.DEFAULT_BUFFER_SEGMENT_SIZE))
            .setBufferDurationsMs(MIN_BUFFER_MS, MAX_BUFFER_MS, PLAYBACK_BUFFER_MS, REBUFFER_MS)
            .setTargetBufferBytes(TARGET_BUFFER_BYTES)
            .setPrioritizeTimeOverSizeThresholds(false)
            .build()
        val p = ExoPlayer.Builder(ctx, DefaultRenderersFactory(ctx).setEnableDecoderFallback(true))
            .setLoadControl(load)
            .setAudioAttributes(
                AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MOVIE).build(),
                true,
            )
            .build()
        p.trackSelectionParameters = p.trackSelectionParameters.buildUpon().setMaxVideoSize(1920, 1080).build()
        p.setVideoSurfaceView(surface)
        p.addListener(object : Player.Listener {
            override fun onPlaybackStateChanged(state: Int) {
                val ch = channel ?: return
                when (state) {
                    Player.STATE_BUFFERING -> {
                        listener.onBuffering(true)
                        handler.removeCallbacks(stall)
                        handler.postDelayed(stall, STALL_MS)
                        if (readyAt > 0) {
                            // Rebuffering during playback: step down to a lower-quality stream if it keeps happening
                            // (3 times a minute) or lasts more than a few seconds.
                            handler.removeCallbacks(stepDownLater)
                            if (rebuffers.record(SystemClock.elapsedRealtime())) stepDown()
                            else handler.postDelayed(stepDownLater, REBUFFER_STEP_DOWN_MS)
                        }
                    }
                    Player.STATE_READY -> {
                        handler.removeCallbacks(stall)
                        handler.removeCallbacks(stepDownLater)
                        if (readyAt == 0L) readyAt = SystemClock.elapsedRealtime()
                        listener.onBuffering(false)
                        failover?.let { listener.onPlaying(ch, it.current, it.current != firstIndex) }
                    }
                    // A live stream that ends (server closed it, ENDLIST) is as dead as one that errors.
                    Player.STATE_ENDED -> fail()
                    else -> Unit
                }
            }

            override fun onPlayerError(error: PlaybackException) {
                if (error.errorCode == PlaybackException.ERROR_CODE_BEHIND_LIVE_WINDOW) {
                    p.seekToDefaultPosition(); p.prepare()
                    return
                }
                fail()
            }
        })
        player = p
        return p
    }

    /** Buffering trouble: go to the next lower-quality stream, if there is one. */
    private fun stepDown() {
        handler.removeCallbacks(stepDownLater)
        if (failover?.stepDown() != null) startCurrent()
    }

    /** The current stream stopped working: retry it if it had been healthy for a while, else try the next one. */
    private fun fail() {
        val ch = channel ?: return
        handler.removeCallbacks(stall)
        val f = failover ?: return
        val playedMs = if (readyAt > 0) SystemClock.elapsedRealtime() - readyAt else 0L
        if (f.failed(playedMs) == null) {
            player?.stop()
            listener.onBuffering(false)
            listener.onFailed(ch)
        } else {
            startCurrent()
        }
    }

    private fun startCurrent() {
        val ch = channel ?: return
        val idx = failover?.current ?: return
        if (idx < 0) { listener.onFailed(ch); return }
        val s = ch.streams[idx]
        if (startedIndex >= 0 && idx != startedIndex) listener.onSwitching(ch, s)
        startedIndex = idx
        readyAt = 0L
        val http = DefaultHttpDataSource.Factory()
            .setUserAgent(s.ua ?: DEFAULT_UA)
            .setAllowCrossProtocolRedirects(true)
            .setConnectTimeoutMs(8_000)
            .setReadTimeoutMs(10_000)
        s.referer?.let { http.setDefaultRequestProperties(mapOf("Referer" to it)) }
        val p = ensurePlayer()
        try {
            p.setMediaSource(DefaultMediaSourceFactory(http).createMediaSource(MediaItem.fromUri(s.url)))
        } catch (_: RuntimeException) {
            // A format this build cannot play (e.g. a DASH .mpd: only the HLS module is included) or a URL
            // that does not parse throws here rather than through onPlayerError. Treat it as a dead stream.
            fail()
            return
        }
        p.prepare()
        p.playWhenReady = true
        handler.removeCallbacks(stall)
        handler.postDelayed(stall, STALL_MS)
    }

    companion object {
        private const val DEFAULT_UA = "Mozilla/5.0 (Linux; Android 9; TV) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120 Safari/537.36"
        private const val MIN_BUFFER_MS = 6_000
        private const val MAX_BUFFER_MS = 12_000
        private const val PLAYBACK_BUFFER_MS = 1_500
        private const val REBUFFER_MS = 3_000
        private const val TARGET_BUFFER_BYTES = 6 * 1024 * 1024
        private const val STALL_MS = 10_000L
        private const val REBUFFER_STEP_DOWN_MS = 4_000L
    }
}
