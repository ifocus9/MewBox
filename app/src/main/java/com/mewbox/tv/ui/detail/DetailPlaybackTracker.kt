package com.mewbox.tv.ui.detail

import com.mewbox.tv.api.config.SettingsRepository
import com.mewbox.tv.bean.Episode
import com.mewbox.tv.bean.Flag
import com.mewbox.tv.bean.History
import com.mewbox.tv.bean.Site
import com.mewbox.tv.bean.Vod
import com.mewbox.tv.domain.SiteRepository
import com.mewbox.tv.player.PlaybackState
import com.mewbox.tv.player.Players
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import timber.log.Timber
import javax.inject.Inject

/**
 * 详情页播放会话与历史进度追踪器：
 * - 维护播放器状态监听机（首帧播放确认、5 秒节流记录、片尾自动跳过、自动下一集）
 * - 历史记录持久化与片头片尾更新
 */
class DetailPlaybackTracker @Inject constructor(
    private val repository: SiteRepository,
    private val settingsRepository: SettingsRepository,
    val players: Players,
) {
    data class ActivePlaySession(
        val site: Site,
        val vod: Vod,
        val flag: Flag,
        val episode: Episode,
        var hasSucceeded: Boolean = false,
        var lastSavedPositionMs: Long = -1L,
        var lastKnownPositionMs: Long = 0L,
        var lastKnownDurationMs: Long = 0L,
    )

    var activeSession: ActivePlaySession? = null
        private set

    private var playbackEndHandled: Boolean = false
    private var endingSkipHandled: Boolean = false
    private val ioScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    fun createSession(site: Site, vod: Vod, flag: Flag, episode: Episode, opening: Long, ending: Long) {
        saveActiveSessionIfSucceeded(opening, ending)
        activeSession = ActivePlaySession(
            site = site,
            vod = vod,
            flag = flag,
            episode = episode,
            hasSucceeded = false,
        )
        playbackEndHandled = false
        endingSkipHandled = false
    }

    fun clearSession() {
        activeSession = null
        playbackEndHandled = false
        endingSkipHandled = false
    }

    /**
     * 监听播放器状态：仅当播放器成功缓冲并开始播放（READY && isPlaying）后，才计入播放历史记录
     */
    fun observePlayerState(
        scope: CoroutineScope,
        getOpening: () -> Long,
        getEnding: () -> Long,
        onPlaybackStarted: (resumeEpisodeText: String) -> Unit,
        onAutoPlayNext: () -> Unit,
    ) {
        scope.launch {
            players.state.collect { playerState ->
                val session = activeSession ?: return@collect
                when (playerState.playbackState) {
                    PlaybackState.READY -> {
                        playbackEndHandled = false
                        session.lastKnownPositionMs = playerState.currentPosition
                        if (playerState.duration > 0L) {
                            session.lastKnownDurationMs = playerState.duration
                        }
                        if (!session.hasSucceeded) {
                            if (playerState.isPlaying) {
                                session.hasSucceeded = true
                                session.lastSavedPositionMs = playerState.currentPosition
                                saveSessionHistory(session, session.lastKnownPositionMs, session.lastKnownDurationMs, getOpening(), getEnding())
                                onPlaybackStarted(session.episode.name)
                            }
                        } else if (!playerState.isPlaying ||
                            kotlin.math.abs(playerState.currentPosition - session.lastSavedPositionMs) >= 5_000L
                        ) {
                            session.lastSavedPositionMs = playerState.currentPosition
                            saveSessionHistory(session, session.lastKnownPositionMs, session.lastKnownDurationMs, getOpening(), getEnding())
                        }

                        // 片尾自动跳过：对齐 FongMi VodPlaybackController
                        val ending = getEnding()
                        if (ending > 0L && playerState.duration > 0L && playerState.isPlaying) {
                            val remaining = playerState.duration - playerState.currentPosition
                            if (remaining <= ending) {
                                if (!endingSkipHandled) {
                                    endingSkipHandled = true
                                    Timber.i("触发片尾自动跳过: remaining=${remaining}ms, ending=${ending}ms")
                                    onAutoPlayNext()
                                }
                            } else {
                                endingSkipHandled = false
                            }
                        }
                    }
                    PlaybackState.ENDED -> {
                        if (!playbackEndHandled) {
                            playbackEndHandled = true
                            if (session.hasSucceeded) {
                                if (playerState.currentPosition > 0L) {
                                    session.lastKnownPositionMs = playerState.currentPosition
                                }
                                if (playerState.duration > 0L) {
                                    session.lastKnownDurationMs = playerState.duration
                                }
                                session.lastSavedPositionMs = session.lastKnownPositionMs
                                saveSessionHistory(session, session.lastKnownPositionMs, session.lastKnownDurationMs, getOpening(), getEnding())

                                onAutoPlayNext()
                            }
                        }
                    }
                    PlaybackState.ERROR -> {
                        playbackEndHandled = false
                        if (!session.hasSucceeded) {
                            activeSession = null
                        }
                    }
                    PlaybackState.IDLE, PlaybackState.BUFFERING -> Unit
                }
            }
        }
    }

    fun saveActiveSessionIfSucceeded(opening: Long, ending: Long) {
        activeSession?.takeIf { it.hasSucceeded }?.let { prev ->
            val playerState = players.state.value
            val pos = playerState.currentPosition.takeIf { it > 0L } ?: prev.lastKnownPositionMs
            val dur = playerState.duration.takeIf { it > 0L } ?: prev.lastKnownDurationMs
            saveSessionHistory(prev, pos, dur, opening, ending)
        }
    }

    fun handleExitFullscreen(opening: Long, ending: Long): Boolean {
        val session = activeSession
        return if (session != null && session.hasSucceeded) {
            val playerState = players.state.value
            val pos = playerState.currentPosition.takeIf { it > 0L } ?: session.lastKnownPositionMs
            val dur = playerState.duration.takeIf { it > 0L } ?: session.lastKnownDurationMs
            players.pause()
            saveSessionHistory(session, pos, dur, opening, ending)
            true
        } else {
            activeSession = null
            players.stop()
            false
        }
    }

    fun handleCleared(opening: Long, ending: Long) {
        val session = activeSession
        if (session != null && session.hasSucceeded) {
            val playerState = players.state.value
            val pos = playerState.currentPosition.takeIf { it > 0L } ?: session.lastKnownPositionMs
            val dur = playerState.duration.takeIf { it > 0L } ?: session.lastKnownDurationMs
            saveSessionHistory(session, pos, dur, opening, ending)
        }
        activeSession = null
        players.stop()
    }

    fun saveSessionHistory(
        session: ActivePlaySession,
        positionMs: Long,
        durationMs: Long,
        opening: Long,
        ending: Long,
    ) {
        val site = session.site
        val vod = session.vod
        val flag = session.flag
        val episode = session.episode
        ioScope.launch {
            try {
                val currentConfigUrl = settingsRepository.currentConfigUrl
                val currentConfigName = repository.getConfigDesc(currentConfigUrl)
                val history = History(
                    key = History.key(site.key, vod.vodId, currentConfigUrl),
                    configUrl = currentConfigUrl,
                    configName = currentConfigName,
                    siteKey = site.key,
                    siteName = site.name,
                    vodId = vod.vodId,
                    vodName = vod.vodName,
                    vodPic = vod.vodPic,
                    vodFlag = flag.flag,
                    episodeName = episode.name,
                    episodeUrl = episode.url,
                    episodeIndex = episode.index,
                    position = positionMs.coerceAtLeast(0L),
                    duration = durationMs.coerceAtLeast(0L),
                    opening = opening,
                    ending = ending,
                )
                repository.saveHistory(history)
                Timber.i("成功记录播放历史: ${vod.vodName} - ${episode.name}, position=$positionMs, key=${history.key}")
            } catch (e: Exception) {
                Timber.e(e, "保存播放历史失败")
            }
        }
    }

    fun persistOpEd(
        site: Site,
        vod: Vod,
        flag: Flag?,
        episode: Episode?,
        opening: Long,
        ending: Long,
    ) {
        ioScope.launch {
            try {
                val existing = repository.getHistory(site.key, vod.vodId)
                val currentConfigUrl = settingsRepository.currentConfigUrl
                val currentConfigName = repository.getConfigDesc(currentConfigUrl)
                val toSave = existing?.copy(
                    opening = opening,
                    ending = ending,
                    updatedAt = System.currentTimeMillis(),
                ) ?: History(
                    key = History.key(site.key, vod.vodId, currentConfigUrl),
                    configUrl = currentConfigUrl,
                    configName = currentConfigName,
                    siteKey = site.key,
                    siteName = site.name,
                    vodId = vod.vodId,
                    vodName = vod.vodName,
                    vodPic = vod.vodPic,
                    vodFlag = flag?.flag.orEmpty(),
                    episodeName = episode?.name.orEmpty(),
                    episodeUrl = episode?.url.orEmpty(),
                    episodeIndex = episode?.index ?: 0,
                    position = players.state.value.currentPosition.coerceAtLeast(0L),
                    duration = players.state.value.duration.coerceAtLeast(0L),
                    opening = opening,
                    ending = ending,
                )
                repository.saveHistory(toSave)
            } catch (e: Exception) {
                Timber.e(e, "持久化保存片头片尾失败")
            }
        }
    }
}

