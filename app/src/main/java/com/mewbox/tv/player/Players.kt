package com.mewbox.tv.player

import android.content.Context
import android.net.Uri
import android.os.Handler
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.Timeline
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import com.mewbox.tv.bean.TrackPreference
import dagger.hilt.android.qualifiers.ApplicationContext
import io.github.anilbeesetti.nextlib.media3ext.ffdecoder.CompatFfmpegAudioRenderer
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
    val headers: Map<String, String> = emptyMap(),
    val title: String = "",
    val speed: Float = 1.0f,
    val resizeMode: Int = 0, // AspectRatioFrameLayout.RESIZE_MODE_FIT
    val videoWidth: Int = 0,
    val videoHeight: Int = 0,
    val networkSpeed: String = "",
    val subtitleTracks: List<PlayerTrack> = emptyList(),
    val audioTracks: List<PlayerTrack> = emptyList(),
    val videoTracks: List<PlayerTrack> = emptyList(),
    val isSubtitleDisabled: Boolean = false,
)

@Singleton
class Players @Inject constructor(
    @ApplicationContext private val context: Context,
    @Named("player") private val playerClient: OkHttpClient,
) {
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private var progressJob: Job? = null
    private var trafficJob: Job? = null
    private val trafficSpeedMeter = TrafficSpeedMeter()

    private var currentHistoryKey: String? = null
    private val targetPreferences = mutableMapOf<Int, TrackPreference>()
    private val restoredTypes = mutableSetOf<Int>()

    @OptIn(UnstableApi::class)
    val exoPlayer: ExoPlayer = run {
        // 对齐 webhtv / FongMi:
        // 1. 使用 ExoBufferBudget 根据设备可用 RAM 与 largeHeap 堆上限自适应计算缓冲配额（大内存手机最高 256MB，低配电视盒子防 OOM）
        val targetBufferBytes = com.mewbox.tv.player.exo.ExoBufferBudget.getEffectiveTargetBytes(context)
        Timber.d("ExoPlayer 初始化缓冲预算: targetBufferBytes=%d MB", targetBufferBytes / (1024 * 1024))

        // 2. bufferForPlaybackMs 设为 1000ms：首帧缓冲够 1 秒立即渲染出画，配合解码器预热实现毫秒级秒开
        // 3. bufferForPlaybackAfterRebufferMs 设为 5000ms：遇网盘抖动重缓冲后蓄满 5 秒再恢复，彻底消除“播1秒卡3秒”连环卡顿
        // 4. minBufferMs=50_000 / maxBufferMs=120_000：对齐 FongMi 50s 低水位并拉宽至 120s 高水位，保持网盘长连接持续拉流
        // 5. setBackBuffer(15_000, true)：保留最近 15 秒关键帧历史数据，短距离回退直接命中内存无需重连网盘
        // 6. setPrioritizeTimeOverSizeThresholds(false)：对齐 webhtv，避免高码率原盘在 minBufferMs 水位处因超过字节上限引发高频拉流/断流震荡
        val loadControl = androidx.media3.exoplayer.DefaultLoadControl.Builder()
            .setBufferDurationsMs(
                /* minBufferMs = */ 50_000,
                /* maxBufferMs = */ 120_000,
                /* bufferForPlaybackMs = */ 1_000,
                /* bufferForPlaybackAfterRebufferMs = */ 5_000,
            )
            .setTargetBufferBytes(targetBufferBytes)
            .setBackBuffer(
                /* backBufferDurationMs = */ 15_000,
                /* retainBackBufferFromKeyframe = */ true,
            )
            .setPrioritizeTimeOverSizeThresholds(false)
            .build()

        // 5. 启用解码器降级容错 + 扩展解码器开启 + 解码器预热（消除首次起播解码器冷启动等待）
        val renderersFactory = object : androidx.media3.exoplayer.DefaultRenderersFactory(context) {
            override fun buildAudioRenderers(
                context: Context,
                extensionRendererMode: Int,
                mediaCodecSelector: androidx.media3.exoplayer.mediacodec.MediaCodecSelector,
                enableDecoderFallback: Boolean,
                audioSink: androidx.media3.exoplayer.audio.AudioSink,
                eventHandler: Handler,
                eventListener: androidx.media3.exoplayer.audio.AudioRendererEventListener,
                out: ArrayList<androidx.media3.exoplayer.Renderer>,
            ) {
                // 1. 优先装载系统默认的 MediaCodec 硬解渲染器（保证 AAC/AC-3 等低功耗硬解）
                super.buildAudioRenderers(
                    context,
                    extensionRendererMode,
                    mediaCodecSelector,
                    enableDecoderFallback,
                    audioSink,
                    eventHandler,
                    eventListener,
                    out,
                )
                // 2. 挂载 FFmpeg 音频扩展渲染器（保底软解 DTS、TrueHD）
                try {
                    out.add(
                        CompatFfmpegAudioRenderer(
                            context,
                            eventHandler,
                            eventListener,
                            audioSink,
                        ),
                    )
                } catch (t: Throwable) {
                    Timber.w(t, "挂载 FFmpeg 音频软解渲染器失败")
                }
            }
        }.apply {
            setEnableDecoderFallback(true)
            setExtensionRendererMode(androidx.media3.exoplayer.DefaultRenderersFactory.EXTENSION_RENDERER_MODE_ON)
            experimentalSetEnableMediaCodecVideoRendererPrewarming(true)
        }

        val player = ExoPlayer.Builder(context, renderersFactory)
            .setLoadControl(loadControl)
            .build()
        // 关键优化（对齐主流网络播放器与 FongMi）：启用 CLOSEST_SYNC 关键帧吸附寻道。
        // 彻底解决高码率 4K/1080p 网盘长 GOP 视频在进度条快进或历史进度起播时，
        // 因 EXACT 模式强行逐帧解码数百个历史帧导致的解码管线堵塞、0KB/s 假死与黑屏卡住
        player.setSeekParameters(androidx.media3.exoplayer.SeekParameters.CLOSEST_SYNC)
        player
    }

    private val _state = MutableStateFlow(PlayerState())
    val state: StateFlow<PlayerState> = _state.asStateFlow()

    private fun resolveDuration(previousDuration: Long): Long {
        val d = exoPlayer.duration
        return if (d > 0L && d != C.TIME_UNSET) d else previousDuration.coerceAtLeast(0L)
    }

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
                        duration = resolveDuration(it.duration),
                        currentPosition = exoPlayer.currentPosition.coerceAtLeast(0L),
                        bufferedPosition = exoPlayer.bufferedPosition.coerceAtLeast(0L),
                    )
                }
                checkProgressPolling()
                checkBufferingPolling(mapped == PlaybackState.BUFFERING)
            }

            override fun onIsPlayingChanged(isPlaying: Boolean) {
                _state.update {
                    it.copy(
                        isPlaying = isPlaying,
                        currentPosition = exoPlayer.currentPosition.coerceAtLeast(0L),
                        duration = resolveDuration(it.duration),
                    )
                }
                checkProgressPolling()
            }

            override fun onTimelineChanged(timeline: Timeline, reason: Int) {
                _state.update {
                    it.copy(
                        duration = resolveDuration(it.duration),
                        currentPosition = exoPlayer.currentPosition.coerceAtLeast(0L),
                        bufferedPosition = exoPlayer.bufferedPosition.coerceAtLeast(0L),
                    )
                }
            }

            override fun onPositionDiscontinuity(
                oldPosition: Player.PositionInfo,
                newPosition: Player.PositionInfo,
                reason: Int,
            ) {
                _state.update {
                    it.copy(
                        duration = resolveDuration(it.duration),
                        currentPosition = newPosition.positionMs.coerceAtLeast(0L),
                        bufferedPosition = exoPlayer.bufferedPosition.coerceAtLeast(0L),
                    )
                }
            }

            override fun onEvents(player: Player, events: Player.Events) {
                if (events.containsAny(
                        Player.EVENT_TIMELINE_CHANGED,
                        Player.EVENT_PLAYBACK_STATE_CHANGED,
                        Player.EVENT_POSITION_DISCONTINUITY,
                        Player.EVENT_MEDIA_ITEM_TRANSITION,
                    )
                ) {
                    _state.update {
                        it.copy(
                            duration = resolveDuration(it.duration),
                            currentPosition = exoPlayer.currentPosition.coerceAtLeast(0L),
                            bufferedPosition = exoPlayer.bufferedPosition.coerceAtLeast(0L),
                        )
                    }
                }
            }

            override fun onVideoSizeChanged(videoSize: androidx.media3.common.VideoSize) {
                _state.update {
                    it.copy(
                        videoWidth = videoSize.width,
                        videoHeight = videoSize.height,
                    )
                }
            }

            override fun onTracksChanged(tracks: androidx.media3.common.Tracks) {
                restorePendingTracks(tracks)
                updateTracks(exoPlayer.currentTracks)
            }

            override fun onPlayerError(error: PlaybackException) {
                Timber.e(error, "ExoPlayer 播放异常")
                val friendlyMessage = getFriendlyErrorMessage(error)

                // 若发生音频解码器初始化失败或不支持，且当前设置了音轨 override，自动恢复系统自适应音轨
                if ((error.errorCode == PlaybackException.ERROR_CODE_DECODER_INIT_FAILED ||
                        error.errorCode == PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED ||
                        error.errorCode == PlaybackException.ERROR_CODE_AUDIO_TRACK_INIT_FAILED) &&
                    targetPreferences.containsKey(C.TRACK_TYPE_AUDIO)
                ) {
                    Timber.w("ExoPlayer 音频解码失败，清除音轨偏好并尝试自适应恢复")
                    targetPreferences.remove(C.TRACK_TYPE_AUDIO)
                    val resetParams = exoPlayer.trackSelectionParameters.buildUpon()
                        .setTrackTypeDisabled(C.TRACK_TYPE_AUDIO, false)
                        .clearOverridesOfType(C.TRACK_TYPE_AUDIO)
                        .build()
                    exoPlayer.trackSelectionParameters = resetParams
                    com.mewbox.tv.util.AppToast.show("音轨解码失败，已自动恢复默认音轨")
                    exoPlayer.prepare()
                    exoPlayer.play()
                    return
                }

                _state.update {
                    it.copy(
                        playbackState = PlaybackState.ERROR,
                        errorMessage = friendlyMessage,
                        networkSpeed = "",
                    )
                }
                stopProgressPolling()
                stopBufferingPolling()
                com.mewbox.tv.util.AppToast.show(friendlyMessage)
            }
        })
    }

    /**
     * 将 ExoPlayer 异常映射为标准中文可读提示（参考 TV ExoErrorMessageProvider）
     */
    private fun getFriendlyErrorMessage(error: PlaybackException): String {
        return when (error.errorCode) {
            PlaybackException.ERROR_CODE_DECODER_INIT_FAILED -> "解码器初始化失败（当前设备不支持该音视频格式）"
            PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED -> "当前设备不支持该音视频编码格式"
            PlaybackException.ERROR_CODE_DECODER_QUERY_FAILED -> "查询设备解码器失败"
            PlaybackException.ERROR_CODE_DECODING_FAILED -> "音视频解码失败"
            PlaybackException.ERROR_CODE_AUDIO_TRACK_INIT_FAILED -> "音频输出轨道初始化失败"
            PlaybackException.ERROR_CODE_AUDIO_TRACK_WRITE_FAILED -> "音频数据写入失败"
            PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED -> "网络连接失败，请检查网络"
            PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT -> "网络连接超时"
            PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS -> "媒体资源请求失败 (${error.message ?: error.errorCodeName})"
            PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED -> "不支持的视频封装格式（如 ISO 原盘镜像等）"
            PlaybackException.ERROR_CODE_PARSING_CONTAINER_MALFORMED -> "视频封装格式损坏"
            PlaybackException.ERROR_CODE_PARSING_MANIFEST_MALFORMED -> "视频播放列表解析错误"
            PlaybackException.ERROR_CODE_TIMEOUT -> "播放响应超时"
            else -> error.localizedMessage?.takeIf { it.isNotBlank() } ?: "播放错误 (${error.errorCodeName})"
        }
    }

    /**
     * 汇报主动拦截或外部校验业务异常，重置播放状态机并同步展示提示
     */
    fun reportError(message: String, url: String = "") {
        stopProgressPolling()
        stopBufferingPolling()
        try {
            exoPlayer.stop()
        } catch (_: Exception) {}
        _state.update {
            it.copy(
                playbackState = PlaybackState.ERROR,
                errorMessage = message,
                playUrl = url.ifEmpty { it.playUrl },
                networkSpeed = "",
            )
        }
        com.mewbox.tv.util.AppToast.show(message)
    }

    @OptIn(UnstableApi::class)
    fun play(
        url: String,
        headers: Map<String, String> = emptyMap(),
        title: String = "",
        startPosition: Long = 0L,
        format: String = "",
    ) {
        if (url.isBlank()) {
            reportError("播放地址为空")
            return
        }

        if (isIsoFormat(url = url, name = title, format = format)) {
            val isoMessage = "检测到该资源为 ISO 原盘镜像，当前内置播放器暂不支持"
            Timber.w("Players: 拦截不支持的 ISO 格式视频流: url=$url, title=$title, format=$format")
            reportError(isoMessage, url)
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
                    } else if (!k.equals("Range", ignoreCase = true)) {
                        sanitized[k.trim()] = v.trim()
                    }
                }
            }
            val effectiveUa = if (!userAgent.isNullOrEmpty()) {
                userAgent!!
            } else {
                DEFAULT_USER_AGENT
            }
            // 确保 OkHttp 请求必定携带标准合法 User-Agent，避免网盘防盗链 WAF 识别到 okhttp 客户端阻断/限速
            httpFactory.setUserAgent(effectiveUa)
            if (sanitized.isNotEmpty()) {
                httpFactory.setDefaultRequestProperties(sanitized)
            }

            // 2. 构造支持大文件 MKV / TS 网络流嗅探与关键帧定位的 ExtractorsFactory
            // - setTsExtractorTimestampSearchBytes: TS 流时间戳嗅探范围扩大 10 倍
            // - FLAG_ENABLE_HDMV_DTS_AUDIO_STREAMS: 开启 TS DTS 高清音频流支持
            // - 移除 setConstantBitrateSeekingEnabled(true): 严禁对变码率 (VBR) 网盘视频开启常码率寻道，
            //   避免按时间比例计算错误字节偏移造成解复用器陷入非同步数据包引发播放中断挂起
            val extractorsFactory = androidx.media3.extractor.DefaultExtractorsFactory()
                .setTsExtractorTimestampSearchBytes(androidx.media3.extractor.ts.TsExtractor.DEFAULT_TIMESTAMP_SEARCH_BYTES * 10)
                .setTsExtractorFlags(androidx.media3.extractor.ts.DefaultTsPayloadReaderFactory.FLAG_ENABLE_HDMV_DTS_AUDIO_STREAMS)

            val defaultDataSourceFactory = DefaultDataSource.Factory(context, httpFactory)
            // 移植自 MewPlayer: 注入 HttpEofRecoveryDataSource 自动拦截并重连网盘 unexpected EOF / 302 临时断流
            val upstreamFactory = com.mewbox.tv.player.exo.HttpEofRecoveryDataSource.Factory(defaultDataSourceFactory)
            // 提高网盘/流媒体网络加载异常的最小重试次数（默认 3 次提升至 6 次），增强对网盘 302 调度与瞬时抖动的容忍度
            val loadErrorHandlingPolicy = androidx.media3.exoplayer.upstream.DefaultLoadErrorHandlingPolicy(/* minimumLoadableRetryCount = */ 6)
            val mediaSourceFactory = DefaultMediaSourceFactory(upstreamFactory, extractorsFactory)
                .setLoadErrorHandlingPolicy(loadErrorHandlingPolicy)

            val mediaItemBuilder = MediaItem.Builder()
                .setUri(Uri.parse(url))
                .setMediaMetadata(MediaMetadata.Builder().setTitle(title).build())

            // 智能推断或对齐 MimeType（对齐 FongMi / MewPlayer）
            // 解决本地代理（如 /m3u8-proxy?url=...）或未以 .m3u8 结尾的流媒体链接被误判为 Progressive 从而报 Source error 的问题
            val inferredMimeType = inferMimeType(url, format)
            if (inferredMimeType != null) {
                mediaItemBuilder.setMimeType(inferredMimeType)
            }

            val mediaItem = mediaItemBuilder.build()
            val mediaSource = if (inferredMimeType == MimeTypes.APPLICATION_M3U8) {
                // 深度优化 HLS 起播：
                // 1. setAllowChunklessPreparation(true)：允许无需等待首个分片下载即可准备完成，大幅缩短起播耗时
                // 2. FLAG_ALLOW_NON_IDR_KEYFRAMES：允许直接在非 IDR 关键帧处起播，无需等待下一个完整 IDR 帧
                // 3. exposeCea608WhenMissingDeclarations = false：避免对字幕特征的无谓嗅探开销
                val hlsExtractorFactory = androidx.media3.exoplayer.hls.DefaultHlsExtractorFactory(
                    androidx.media3.extractor.ts.DefaultTsPayloadReaderFactory.FLAG_ALLOW_NON_IDR_KEYFRAMES,
                    /* exposeCea608WhenMissingDeclarations = */ false,
                )
                androidx.media3.exoplayer.hls.HlsMediaSource.Factory(upstreamFactory)
                    .setExtractorFactory(hlsExtractorFactory)
                    .setAllowChunklessPreparation(true)
                    .setLoadErrorHandlingPolicy(loadErrorHandlingPolicy)
                    .createMediaSource(mediaItem)
            } else {
                mediaSourceFactory.createMediaSource(mediaItem)
            }

            val effectiveHeaders = LinkedHashMap<String, String>()
            effectiveHeaders["User-Agent"] = effectiveUa
            sanitized.forEach { (k, v) ->
                effectiveHeaders[k] = v
            }

            _state.update {
                it.copy(
                    playbackState = PlaybackState.BUFFERING,
                    isPlaying = false,
                    errorMessage = null,
                    playUrl = url,
                    headers = effectiveHeaders,
                    title = title,
                    currentPosition = startPosition,
                    duration = 0L,
                    bufferedPosition = startPosition,
                    videoWidth = 0,
                    videoHeight = 0,
                    networkSpeed = "0KB/s",
                )
            }
            checkBufferingPolling(true)

            restoredTypes.clear()
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
        if (exoPlayer.playbackState == Player.STATE_ENDED) {
            exoPlayer.seekTo(0L)
        }
        exoPlayer.play()
    }

    fun togglePlayPause() {
        if (exoPlayer.isPlaying) pause() else resume()
    }

    fun seekTo(positionMs: Long) {
        val curDuration = resolveDuration(_state.value.duration)
        val target = if (curDuration > 0L) {
            positionMs.coerceIn(0L, curDuration)
        } else {
            positionMs.coerceAtLeast(0L)
        }
        Timber.d("seekTo: requested=%d, target=%d, duration=%d", positionMs, target, curDuration)
        _state.update {
            it.copy(
                playbackState = PlaybackState.BUFFERING,
                currentPosition = target,
                networkSpeed = "0KB/s",
            )
        }
        checkBufferingPolling(true)
        exoPlayer.seekTo(target)
        exoPlayer.play()
    }

    fun seekBy(offsetMs: Long) {
        val current = exoPlayer.currentPosition.coerceAtLeast(0L)
        seekTo(current + offsetMs)
    }

    fun setPlaybackSpeed(speed: Float) {
        exoPlayer.setPlaybackSpeed(speed)
        _state.update { it.copy(speed = speed) }
    }

    fun setResizeMode(mode: Int) {
        _state.update { it.copy(resizeMode = mode) }
    }

    fun stop() {
        stopProgressPolling()
        stopBufferingPolling()
        exoPlayer.stop()
        exoPlayer.clearMediaItems()
        _state.update {
            it.copy(
                playbackState = PlaybackState.IDLE,
                isPlaying = false,
                currentPosition = 0L,
                duration = 0L,
                videoWidth = 0,
                videoHeight = 0,
                networkSpeed = "",
                subtitleTracks = emptyList(),
                audioTracks = emptyList(),
                videoTracks = emptyList(),
            )
        }
    }

    fun release() {
        stopProgressPolling()
        stopBufferingPolling()
        exoPlayer.release()
    }

    /**
     * 设置当前播放会话（剧集 Key 与已持久化的轨道偏好）。
     * 会在切集或从历史进度进入时被调用。
     */
    fun setPlaybackSession(historyKey: String, preferences: List<TrackPreference>) {
        currentHistoryKey = historyKey
        targetPreferences.clear()
        restoredTypes.clear()
        for (pref in preferences) {
            targetPreferences[pref.type] = pref
        }
        val tracks = exoPlayer.currentTracks
        if (!tracks.isEmpty) {
            restorePendingTracks(tracks)
            updateTracks(exoPlayer.currentTracks)
        }
    }

    /**
     * 依据当前偏好智能恢复未应用的字幕/音轨/视轨
     */
    private fun restorePendingTracks(tracks: androidx.media3.common.Tracks) {
        if (tracks.isEmpty || targetPreferences.isEmpty()) return
        var paramsBuilder: androidx.media3.common.TrackSelectionParameters.Builder? = null

        for ((type, preference) in targetPreferences) {
            if (restoredTypes.contains(type)) continue

            if (preference.isDisabled) {
                // 恢复关闭字幕
                if (paramsBuilder == null) {
                    paramsBuilder = exoPlayer.trackSelectionParameters.buildUpon()
                }
                paramsBuilder.setTrackTypeDisabled(type, true)
                paramsBuilder.clearOverridesOfType(type)
                restoredTypes.add(type)
                Timber.d("restorePendingTracks: 成功恢复轨道禁用状态 type=%d", type)
                continue
            }

            // 智能多维匹配
            val match = PlayerTrackMatcher.findBestMatch(tracks, preference)
            if (match != null) {
                // 安全防线：若历史记录匹配到的偏好轨道在当前设备不受支持，跳过恢复，避免起播假死
                if (!match.group.isTrackSupported(match.trackIndex)) {
                    Timber.w("restorePendingTracks: 偏好匹配到的轨道不受当前设备支持，跳过恢复 type=%d", type)
                    restoredTypes.add(type)
                    continue
                }
                val override = androidx.media3.common.TrackSelectionOverride(
                    match.group.mediaTrackGroup,
                    match.trackIndex,
                )
                if (paramsBuilder == null) {
                    paramsBuilder = exoPlayer.trackSelectionParameters.buildUpon()
                }
                paramsBuilder.setTrackTypeDisabled(type, false)
                paramsBuilder.setOverrideForType(override)
                restoredTypes.add(type)
                Timber.d(
                    "restorePendingTracks: 智能恢复轨道 type=%d, candidate=%s, score=%d",
                    type,
                    match.format.label ?: match.format.language,
                    match.score,
                )
            }
        }

        if (paramsBuilder != null) {
            exoPlayer.trackSelectionParameters = paramsBuilder.build()
        }
    }

    /**
     * 解析 ExoPlayer 当前所有的轨道组并映射为响应式 PlayerTrack 实体
     */
    private fun updateTracks(tracks: androidx.media3.common.Tracks) {
        val disabledTypes = exoPlayer.trackSelectionParameters.disabledTrackTypes
        val isSubDisabled = disabledTypes.contains(C.TRACK_TYPE_TEXT)

        val subtitles = mutableListOf<PlayerTrack>()
        val audios = mutableListOf<PlayerTrack>()
        val videos = mutableListOf<PlayerTrack>()

        var textGroupIdx = 0
        var audioGroupIdx = 0
        var videoGroupIdx = 0

        for (group in tracks.groups) {
            when (group.type) {
                C.TRACK_TYPE_TEXT -> {
                    for (i in 0 until group.length) {
                        subtitles.add(createPlayerTrack(group, i, textGroupIdx))
                    }
                    textGroupIdx++
                }
                C.TRACK_TYPE_AUDIO -> {
                    for (i in 0 until group.length) {
                        audios.add(createPlayerTrack(group, i, audioGroupIdx))
                    }
                    audioGroupIdx++
                }
                C.TRACK_TYPE_VIDEO -> {
                    for (i in 0 until group.length) {
                        videos.add(createPlayerTrack(group, i, videoGroupIdx))
                    }
                    videoGroupIdx++
                }
            }
        }

        _state.update {
            it.copy(
                subtitleTracks = subtitles,
                audioTracks = audios,
                videoTracks = videos,
                isSubtitleDisabled = isSubDisabled,
            )
        }
    }

    private fun createPlayerTrack(
        group: androidx.media3.common.Tracks.Group,
        trackIndex: Int,
        groupIndex: Int,
    ): PlayerTrack {
        val format = group.getTrackFormat(trackIndex)
        val isSelected = group.isTrackSelected(trackIndex)
        val isSupported = group.isTrackSupported(trackIndex)
        val type = group.type

        val name = PlayerTrackFormatter.formatTrackTitle(format, type, trackIndex)
        val details = PlayerTrackFormatter.formatTrackDetails(format, type)
        val formatDesc = PlayerTrackMatcher.describeFormat(format)

        return PlayerTrack(
            id = "${type}_${groupIndex}_${trackIndex}",
            name = name,
            type = type,
            isSelected = isSelected,
            isSupported = isSupported,
            groupIndex = groupIndex,
            trackIndex = trackIndex,
            language = format.language,
            label = format.label,
            width = if (format.width != C.LENGTH_UNSET) format.width else 0,
            height = if (format.height != C.LENGTH_UNSET) format.height else 0,
            details = details,
            formatDescription = formatDesc,
        )
    }

    /**
     * 选中指定轨道（对齐 FongMi TrackSelectionOverride）并更新当前会话偏好
     * @return true 表示切换成功，false 表示目标轨道不受当前设备解码器支持
     */
    fun selectTrack(track: PlayerTrack, onPersist: ((TrackPreference) -> Unit)? = null): Boolean {
        val groups = exoPlayer.currentTracks.groups.filter { it.type == track.type }
        val targetGroup = groups.getOrNull(track.groupIndex) ?: return false

        // 安全防线：若设备硬件/系统解码器不支持该轨道（如手机缺失 DTS 硬解），拒绝强行 Override，避免播放器静默假死
        if (!targetGroup.isTrackSupported(track.trackIndex)) {
            Timber.w(
                "selectTrack: 目标轨道不受设备解码器支持 type=%d, groupIdx=%d, trackIdx=%d, name=%s",
                track.type,
                track.groupIndex,
                track.trackIndex,
                track.name,
            )
            return false
        }

        val override = androidx.media3.common.TrackSelectionOverride(targetGroup.mediaTrackGroup, track.trackIndex)
        val params = exoPlayer.trackSelectionParameters
            .buildUpon()
            .setTrackTypeDisabled(track.type, false)
            .setOverrideForType(override)
            .build()
        exoPlayer.trackSelectionParameters = params

        val pref = track.toPreference(currentHistoryKey.orEmpty())
        targetPreferences[track.type] = pref
        restoredTypes.add(track.type)
        onPersist?.invoke(pref)

        updateTracks(exoPlayer.currentTracks)
        return true
    }

    /**
     * 禁用或开启指定轨道类型（如关闭字幕）
     */
    fun setTrackTypeDisabled(type: Int, disabled: Boolean, onPersist: ((TrackPreference) -> Unit)? = null) {
        val builder = exoPlayer.trackSelectionParameters
            .buildUpon()
            .setTrackTypeDisabled(type, disabled)
        if (disabled) {
            builder.clearOverridesOfType(type)
            val pref = TrackPreference(
                key = currentHistoryKey.orEmpty(),
                type = type,
                name = if (type == C.TRACK_TYPE_TEXT) "关闭字幕" else "禁用",
                format = TrackPreference.DISABLED_FORMAT,
                isDisabled = true,
                updatedAt = System.currentTimeMillis(),
            )
            targetPreferences[type] = pref
            restoredTypes.add(type)
            onPersist?.invoke(pref)
        }
        exoPlayer.trackSelectionParameters = builder.build()
        updateTracks(exoPlayer.currentTracks)
    }

    /**
     * 重置轨道类型到系统自适应默认配置
     */
    fun resetTrackTypeToDefault(type: Int, onPersist: (() -> Unit)? = null) {
        val params = exoPlayer.trackSelectionParameters
            .buildUpon()
            .setTrackTypeDisabled(type, false)
            .clearOverridesOfType(type)
            .build()
        exoPlayer.trackSelectionParameters = params

        targetPreferences.remove(type)
        restoredTypes.add(type)
        onPersist?.invoke()

        updateTracks(exoPlayer.currentTracks)
    }

    private fun checkBufferingPolling(isBuffering: Boolean) {
        if (isBuffering) {
            startBufferingPolling()
        } else {
            stopBufferingPolling()
        }
    }

    private fun startBufferingPolling() {
        if (trafficJob?.isActive == true) return
        trafficSpeedMeter.reset()
        val initialSpeed = trafficSpeedMeter.getSpeed() ?: ""
        _state.update { it.copy(networkSpeed = initialSpeed) }
        trafficJob = scope.launch(Dispatchers.IO) {
            while (isActive) {
                delay(1000L)
                val speed = trafficSpeedMeter.getSpeed() ?: ""
                _state.update { it.copy(networkSpeed = speed) }
            }
        }
    }

    private fun stopBufferingPolling() {
        trafficJob?.cancel()
        trafficJob = null
        trafficSpeedMeter.reset()
        _state.update { it.copy(networkSpeed = "") }
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
                        duration = resolveDuration(it.duration),
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
        const val DEFAULT_USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"

        /**
         * 智能推断媒体流 MIME 类型（对齐 FongMi / MewPlayer MpvLifecycleCoordinator）
         * 解决 Node.js 本地代理 (/m3u8-proxy?url=...) 及 URL 参数带 .m3u8 等无直接文件后缀链接的识别问题
         */
        fun inferMimeType(url: String, format: String = ""): String? {
            val f = format.trim().lowercase(Locale.ROOT)
            if (f == "hls" || f == "m3u8") return MimeTypes.APPLICATION_M3U8
            if (f == "dash" || f == "mpd") return MimeTypes.APPLICATION_MPD
            if (f == "ss" || f == "ism") return MimeTypes.APPLICATION_SS
            if (f == "rtsp") return MimeTypes.APPLICATION_RTSP

            val lower = url.trim().lowercase(Locale.ROOT)
            return when {
                lower.contains(".m3u8") || lower.contains("/m3u8-proxy") || lower.contains(".m3u") ||
                    isProxyM3u8(url) ->
                    MimeTypes.APPLICATION_M3U8
                lower.contains(".mpd") ->
                    MimeTypes.APPLICATION_MPD
                lower.contains(".ism") ->
                    MimeTypes.APPLICATION_SS
                lower.startsWith("rtsp://") ->
                    MimeTypes.APPLICATION_RTSP
                else -> null
            }
        }

        private fun isProxyM3u8(url: String): Boolean {
            if (!url.contains("/proxy") && !url.contains("/m3u8-proxy")) return false
            return try {
                val raw = if (url.contains("://")) url.substringAfter("://").substringAfter('/', "") else url
                val segments = raw.split('/', '?', '&', '=').filter { it.length > 15 }
                segments.any { segment ->
                    val clean = segment.substringBefore(".m3u8")
                    val decoded = try {
                        try {
                            String(java.util.Base64.getUrlDecoder().decode(clean), Charsets.UTF_8)
                        } catch (_: Exception) {
                            String(java.util.Base64.getDecoder().decode(clean), Charsets.UTF_8)
                        }
                    } catch (_: Throwable) {
                        try {
                            java.net.URLDecoder.decode(clean, "UTF-8")
                        } catch (_: Throwable) { "" }
                    }
                    decoded.contains(".m3u8", ignoreCase = true) || decoded.contains("#EXTM3U", ignoreCase = true)
                }
            } catch (_: Exception) {
                false
            }
        }

        /**
         * 判定是否为光盘镜像格式（ISO / DVD / Blu-ray 原盘）
         * 对齐 FongMi / MewPlayer MpvLifecycleCoordinator 的 isLikelyIso 规则，
         * 并结合 URL、剧集名称及 Format/MimeType 多维判定
         */
        fun isIsoFormat(url: String, name: String = "", format: String = ""): Boolean {
            val f = format.trim().lowercase(Locale.ROOT)
            if (f == "iso" || f.contains("iso") || f.contains("bluray") || f.contains("dvd") || f.contains("udf")) return true

            val n = name.trim().lowercase(Locale.ROOT)
            if (n.endsWith(".iso") || n.contains(".iso.") || n.contains(".iso ") || n.contains(".iso-") || n.contains(".iso_")) return true

            val u = url.trim().lowercase(Locale.ROOT)
            if (u.startsWith("webhtv-dvdiso://") || u.startsWith("dvd://") || u.startsWith("bd://")) return true
            val cleanUrl = u.substringBefore('?').substringBefore('#')
            if (cleanUrl.endsWith(".iso")) return true
            if (u.contains(".iso?") || u.contains(".iso&") || u.contains(".iso#") || u.contains("/iso/")) return true

            return false
        }

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
