package com.mewbox.tv.player

import android.content.Context
import android.net.Uri
import androidx.annotation.OptIn
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import timber.log.Timber
import java.util.Locale
import javax.inject.Inject
import javax.inject.Named
import javax.inject.Singleton

enum class PlaybackState {
    IDLE,
    BUFFERING,
    READY,
    ENDED,
    ERROR
}

data class PlayerState(
    val playbackState: PlaybackState = PlaybackState.IDLE,
    val isPlaying: Boolean = false,
    val currentPosition: Long = 0L,
    val duration: Long = 0L,
    val bufferedPosition: Long = 0L,
    val errorMessage: String? = null,
    val playUrl: String = "",
    val title: String = "",
)

@Singleton
class Players @Inject constructor(
    @ApplicationContext private val context: Context,
    @Named("player") private val playerClient: OkHttpClient,
) {
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private var progressJob: Job? = null

    @OptIn(UnstableApi::class)
    val exoPlayer: ExoPlayer = run {
        // 对齐 FongMi / MewPlayer:
        // 1. 使用 ExoBufferBudget 根据设备可用 RAM 与堆上限自适应计算缓冲大小，防止 1G/2G 电视盒子 OOM，并在高配设备上提供充足缓冲
        val targetBufferBytes = com.mewbox.tv.player.exo.ExoBufferBudget.getEffectiveTargetBytes(context)

        // 2. bufferForPlaybackMs 降为 1000ms（极速起播，不再等待默认的 2500ms 冗余缓冲）
        // 3. bufferForPlaybackAfterRebufferMs 设为 2000ms
        // 4. setPrioritizeTimeOverSizeThresholds(true): 极其关键，以时间时长为准，避免大码率 4K 视频因等待超大内存 buffer 填满而迟迟不起播
        val loadControl = androidx.media3.exoplayer.DefaultLoadControl.Builder()
            .setBufferDurationsMs(
                /* minBufferMs = */ 30_000,
                /* maxBufferMs = */ 60_000,
                /* bufferForPlaybackMs = */ 1_000,
                /* bufferForPlaybackAfterRebufferMs = */ 2_000,
            )
            .setTargetBufferBytes(targetBufferBytes)
            .setPrioritizeTimeOverSizeThresholds(true)
            .build()

        // 5. 启用解码器降级容错 + 扩展解码器开启
        val renderersFactory = androidx.media3.exoplayer.DefaultRenderersFactory(context).apply {
            setEnableDecoderFallback(true)
            setExtensionRendererMode(androidx.media3.exoplayer.DefaultRenderersFactory.EXTENSION_RENDERER_MODE_ON)
        }

        ExoPlayer.Builder(context, renderersFactory)
            .setLoadControl(loadControl)
            .build()
    }

    private val _state = MutableStateFlow(PlayerState())
    val state: StateFlow<PlayerState> = _state.asStateFlow()

    init {
        exoPlayer.addListener(object : Player.Listener {
            override fun onPlaybackStateChanged(playbackState: Int) {
                val mapped = when (playbackState) {
                    Player.STATE_IDLE -> PlaybackState.IDLE
                    Player.STATE_BUFFERING -> PlaybackState.BUFFERING
                    Player.STATE_READY -> PlaybackState.READY
                    Player.STATE_ENDED -> PlaybackState.ENDED
                    else -> PlaybackState.IDLE
                }
                _state.update {
                    it.copy(
                        playbackState = mapped,
                        duration = exoPlayer.duration.coerceAtLeast(0L),
                        currentPosition = exoPlayer.currentPosition.coerceAtLeast(0L),
                        bufferedPosition = exoPlayer.bufferedPosition.coerceAtLeast(0L),
                    )
                }
                checkProgressPolling()
            }

            override fun onIsPlayingChanged(isPlaying: Boolean) {
                _state.update {
                    it.copy(
                        isPlaying = isPlaying,
                        currentPosition = exoPlayer.currentPosition.coerceAtLeast(0L),
                        duration = exoPlayer.duration.coerceAtLeast(0L),
                    )
                }
                checkProgressPolling()
            }

            override fun onPlayerError(error: PlaybackException) {
                Timber.e(error, "ExoPlayer 播放异常")
                _state.update {
                    it.copy(
                        playbackState = PlaybackState.ERROR,
                        errorMessage = error.localizedMessage ?: "播放错误 (${error.errorCodeName})",
                    )
                }
                stopProgressPolling()
            }
        })
    }

    @OptIn(UnstableApi::class)
    fun play(
        url: String,
        headers: Map<String, String> = emptyMap(),
        title: String = "",
        startPosition: Long = 0L,
    ) {
        if (url.isBlank()) {
            _state.update { it.copy(playbackState = PlaybackState.ERROR, errorMessage = "播放地址为空") }
            return
        }

        try {
            // 1. 净化并装配 OkHttp Headers
            val httpFactory = OkHttpDataSource.Factory(playerClient)
            val sanitized = LinkedHashMap<String, String>()
            var userAgent: String? = null

            headers.forEach { (k, v) ->
                if (k.isNotBlank() && v.isNotBlank()) {
                    if (k.equals("User-Agent", ignoreCase = true)) {
                        userAgent = v.trim()
                    } else {
                        sanitized[k.trim()] = v.trim()
                    }
                }
            }
            if (!userAgent.isNullOrEmpty()) {
                httpFactory.setUserAgent(userAgent)
            }
            if (sanitized.isNotEmpty()) {
                httpFactory.setDefaultRequestProperties(sanitized)
            }

            // 2. 构造支持大文件 MKV / TS 网络流嗅探的 ExtractorsFactory（对齐 MewPlayer / FongMi）
            val extractorsFactory = androidx.media3.extractor.DefaultExtractorsFactory()
                .setTsExtractorTimestampSearchBytes(androidx.media3.extractor.ts.TsExtractor.DEFAULT_TIMESTAMP_SEARCH_BYTES * 10)
                .setTsExtractorFlags(androidx.media3.extractor.ts.DefaultTsPayloadReaderFactory.FLAG_ENABLE_HDMV_DTS_AUDIO_STREAMS)
                .setMatroskaExtractorFlags(androidx.media3.extractor.mkv.MatroskaExtractor.FLAG_DISABLE_SEEK_FOR_CUES)

            val defaultDataSourceFactory = DefaultDataSource.Factory(context, httpFactory)
            // 移植自 MewPlayer: 注入 HttpEofRecoveryDataSource 自动拦截并重连网盘 unexpected EOF / 302 临时断流
            val upstreamFactory = com.mewbox.tv.player.exo.HttpEofRecoveryDataSource.Factory(defaultDataSourceFactory)
            val mediaSourceFactory = DefaultMediaSourceFactory(upstreamFactory, extractorsFactory)

            val mediaItem = MediaItem.Builder()
                .setUri(Uri.parse(url))
                .setMediaMetadata(MediaMetadata.Builder().setTitle(title).build())
                .build()

            val mediaSource = mediaSourceFactory.createMediaSource(mediaItem)

            _state.update {
                it.copy(
                    playbackState = PlaybackState.BUFFERING,
                    errorMessage = null,
                    playUrl = url,
                    title = title,
                    currentPosition = startPosition,
                )
            }

            exoPlayer.setMediaSource(mediaSource, startPosition)
            exoPlayer.prepare()
            exoPlayer.playWhenReady = true
        } catch (e: Exception) {
            Timber.e(e, "初始化播放源失败: $url")
            _state.update {
                it.copy(
                    playbackState = PlaybackState.ERROR,
                    errorMessage = "初始化播放源失败: ${e.message}",
                )
            }
        }
    }

    fun pause() {
        exoPlayer.pause()
    }

    fun resume() {
        exoPlayer.play()
    }

    fun togglePlayPause() {
        if (exoPlayer.isPlaying) pause() else resume()
    }

    fun seekTo(positionMs: Long) {
        exoPlayer.seekTo(positionMs.coerceAtLeast(0L))
        _state.update { it.copy(currentPosition = positionMs) }
    }

    fun stop() {
        stopProgressPolling()
        exoPlayer.stop()
        _state.update {
            it.copy(
                playbackState = PlaybackState.IDLE,
                isPlaying = false,
                currentPosition = 0L,
                duration = 0L,
            )
        }
    }

    fun release() {
        stopProgressPolling()
        exoPlayer.release()
    }

    private fun checkProgressPolling() {
        if (exoPlayer.isPlaying) {
            startProgressPolling()
        } else {
            stopProgressPolling()
        }
    }

    private fun startProgressPolling() {
        if (progressJob?.isActive == true) return
        progressJob = scope.launch {
            while (isActive) {
                _state.update {
                    it.copy(
                        currentPosition = exoPlayer.currentPosition.coerceAtLeast(0L),
                        duration = exoPlayer.duration.coerceAtLeast(0L),
                        bufferedPosition = exoPlayer.bufferedPosition.coerceAtLeast(0L),
                    )
                }
                delay(500L)
            }
        }
    }

    private fun stopProgressPolling() {
        progressJob?.cancel()
        progressJob = null
    }

    companion object {
        fun formatDuration(ms: Long): String {
            if (ms <= 0L) return "00:00"
            val totalSeconds = ms / 1000
            val seconds = totalSeconds % 60
            val minutes = (totalSeconds / 60) % 60
            val hours = totalSeconds / 3600
            return if (hours > 0) {
                String.format(Locale.getDefault(), "%02d:%02d:%02d", hours, minutes, seconds)
            } else {
                String.format(Locale.getDefault(), "%02d:%02d", minutes, seconds)
            }
        }
    }
}
