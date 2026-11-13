package tv.cypher.app

import android.content.Context
import android.os.Handler
import android.os.Looper
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
import tv.cypher.core.StreamFailover

/**
 * Plays one channel at a time with the headers each stream needs. On an error or a stall it tries the
 * channel's backup streams in order, last-known-good first.
 */
@UnstableApi
class PlayerController(
    private val ctx: Context,
    private val surface: SurfaceView,
    private val listener: Listener,
) {
    interface Listener {
        fun onPlaying(channel: Channel, streamIndex: Int)
        fun onBuffering(buffering: Boolean)
        fun onFailed(channel: Channel)
    }

    private val handler = Handler(Looper.getMainLooper())
    private var player: ExoPlayer? = null
    private var channel: Channel? = null
    private var failover: StreamFailover? = null
    private var playedOk = false
    private val stall = Runnable { advance() }

    val hasPlayer: Boolean get() = player != null

    fun play(ch: Channel, lastGoodIndex: Int) {
        channel = ch
        failover = StreamFailover(ch.streams.size, lastGoodIndex)
        playedOk = false
        startCurrent()
    }

    fun stop() {
        handler.removeCallbacks(stall)
        player?.stop()
        player?.clearMediaItems()
    }

    fun release() {
        handler.removeCallbacks(stall)
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
                    }
                    Player.STATE_READY -> {
                        handler.removeCallbacks(stall)
                        playedOk = true
                        listener.onBuffering(false)
                        failover?.let { listener.onPlaying(ch, it.current) }
                    }
                    else -> Unit
                }
            }

            override fun onPlayerError(error: PlaybackException) {
                if (error.errorCode == PlaybackException.ERROR_CODE_BEHIND_LIVE_WINDOW) {
                    p.seekToDefaultPosition(); p.prepare()
                    return
                }
                advance()
            }
        })
        player = p
        return p
    }

    private fun advance() {
        val ch = channel ?: return
        handler.removeCallbacks(stall)
        val f = failover ?: return
        if (playedOk) {
            // It was working and then broke: retry this stream first, then the others.
            playedOk = false
            failover = StreamFailover(ch.streams.size, f.current)
            startCurrent()
            return
        }
        if (f.advance() == null) {
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
        val http = DefaultHttpDataSource.Factory()
            .setUserAgent(s.ua ?: DEFAULT_UA)
            .setAllowCrossProtocolRedirects(true)
            .setConnectTimeoutMs(8_000)
            .setReadTimeoutMs(10_000)
        s.referer?.let { http.setDefaultRequestProperties(mapOf("Referer" to it)) }
        val source = DefaultMediaSourceFactory(http).createMediaSource(MediaItem.fromUri(s.url))
        val p = ensurePlayer()
        p.setMediaSource(source)
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
    }
}
