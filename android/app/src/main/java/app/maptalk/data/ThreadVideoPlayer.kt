package app.maptalk.data

import android.content.Context
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.LeastRecentlyUsedCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import java.io.File

/**
 * One shared ExoPlayer for in-thread video so only one clip plays at a time,
 * with a ~100 MB disk cache for remote MP4s.
 */
@OptIn(UnstableApi::class)
object ThreadVideoPlayer {

    private const val CACHE_BYTES = 100L * 1024L * 1024L

    @Volatile
    private var player: ExoPlayer? = null

    @Volatile
    private var cache: SimpleCache? = null

    @Volatile
    var activePath: String? = null
        private set

    @Volatile
    var isMuted: Boolean = false
        private set

    fun player(context: Context): ExoPlayer {
        player?.let { return it }
        synchronized(this) {
            player?.let { return it }
            val app = context.applicationContext
            val exo = ExoPlayer.Builder(app)
                .setMediaSourceFactory(
                    DefaultMediaSourceFactory(cacheDataSourceFactory(app)),
                )
                .build()
                .also { created ->
                    created.volume = if (isMuted) 0f else 1f
                    created.addListener(
                        object : Player.Listener {
                            override fun onPlaybackStateChanged(playbackState: Int) {
                                if (playbackState == Player.STATE_ENDED) {
                                    created.seekTo(0)
                                    created.pause()
                                    activePath = null
                                }
                            }
                        },
                    )
                }
            player = exo
            return exo
        }
    }

    fun play(context: Context, path: String) {
        val exo = player(context)
        exo.volume = if (isMuted) 0f else 1f
        if (activePath == path && exo.isPlaying) {
            exo.pause()
            activePath = null
            return
        }
        if (activePath == path) {
            exo.play()
            return
        }
        activePath = path
        exo.setMediaItem(MediaItem.fromUri(path))
        exo.prepare()
        exo.playWhenReady = true
    }

    fun toggleMute(context: Context) {
        isMuted = !isMuted
        player(context).volume = if (isMuted) 0f else 1f
    }

    fun pauseIfPlaying(path: String) {
        if (activePath != path) return
        player?.pause()
        activePath = null
    }

    fun release() {
        synchronized(this) {
            player?.release()
            player = null
            activePath = null
            cache?.release()
            cache = null
        }
    }

    private fun cacheDataSourceFactory(context: Context): CacheDataSource.Factory {
        val simpleCache = cache ?: synchronized(this) {
            cache ?: SimpleCache(
                File(context.cacheDir, "maptalk-video-cache"),
                LeastRecentlyUsedCacheEvictor(CACHE_BYTES),
                StandaloneDatabaseProvider(context),
            ).also { cache = it }
        }
        return CacheDataSource.Factory()
            .setCache(simpleCache)
            .setUpstreamDataSourceFactory(
                DefaultHttpDataSource.Factory().setUserAgent("MapTalk"),
            )
            .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)
    }
}
