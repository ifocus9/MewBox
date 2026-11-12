package com.mewbox.tv.ui.history

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.mewbox.tv.api.config.SettingsRepository
import com.mewbox.tv.api.douban.DoubanApiException
import com.mewbox.tv.api.douban.DoubanRepository
import com.mewbox.tv.api.douban.WishItem
import com.mewbox.tv.api.douban.WishStatus
import com.mewbox.tv.api.douban.WishUiState
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import timber.log.Timber
import javax.inject.Inject

/**
 * 豆瓣想看分段的 UI 状态容器：只负责状态翻译与调度，
 * 分页游标 / 去重 / 节流 / 退避重试全部委托 DoubanRepository。
 */
@HiltViewModel
class WishViewModel @Inject constructor(
    private val doubanRepository: DoubanRepository,
    private val settingsRepository: SettingsRepository,
) : ViewModel() {

    private val _uiState = MutableStateFlow(WishUiState())
    val uiState: StateFlow<WishUiState> = _uiState.asStateFlow()

    private var uid: String = ""
    private var syncJob: Job? = null

    init {
        // uid 变化（首次设置 / 修改 / 清除）即重置状态：有 uid 自动同步，无 uid 回到引导态
        viewModelScope.launch {
            settingsRepository.doubanUidFlow.collect { newUid ->
                if (newUid != uid) {
                    uid = newUid
                    if (newUid.isBlank()) {
                        syncJob?.cancel()
                        _uiState.value = WishUiState(status = WishStatus.NoUid)
                    } else {
                        loadFirstPage()
                    }
                }
            }
        }
    }

    /** uid 弹窗保存入口：归一化后落 DataStore，uid flow 变化会自动触发重新同步。 */
    fun saveUid(input: String) {
        val normalized = SettingsRepository.normalizeDoubanUid(input) ?: return
        viewModelScope.launch {
            settingsRepository.setDoubanUid(normalized)
        }
    }

    /** 进入想看分段时兜底：uid 已存在但尚未加载过（如 flow 冷启动竞态）。 */
    fun ensureLoaded() {
        if (uid.isBlank()) {
            _uiState.update { if (it.status != WishStatus.NoUid) it.copy(status = WishStatus.NoUid) else it }
            return
        }
        val current = _uiState.value
        if (current.items.isEmpty() &&
            current.status in listOf(WishStatus.Idle, WishStatus.NoUid)
        ) {
            loadFirstPage()
        }
    }

    /** 同步按钮：距上次成功同步 30s 内静默跳过，长按 / 空态重试走 forced。 */
    fun sync(forced: Boolean = false) {
        if (uid.isBlank()) {
            _uiState.update { it.copy(status = WishStatus.NoUid) }
            return
        }
        if (!forced && System.currentTimeMillis() - doubanRepository.lastSyncAt < SYNC_THROTTLE_MS) {
            Timber.d("豆瓣想看同步节流：距上次成功不足 30s，跳过")
            return
        }
        loadFirstPage()
    }

    /** 滚动加载下一页：并发防抖由状态机 + Repository Mutex 双重保障。 */
    fun loadMore() {
        val current = _uiState.value
        if (current.status in listOf(WishStatus.Loading, WishStatus.LoadingMore, WishStatus.Refreshing)) return
        if (!current.hasMore || current.items.isEmpty()) return
        _uiState.update { it.copy(status = WishStatus.LoadingMore) }
        syncJob = viewModelScope.launch {
            try {
                doubanRepository.loadMore(uid).let { page ->
                    _uiState.update {
                        it.copy(
                            items = doubanRepository.items,
                            total = doubanRepository.totalCount,
                            hasMore = page.hasMore,
                            status = WishStatus.Idle,
                            errorMessage = if (page.reachedPageLimit) "已达单次同步条数上限" else null,
                        )
                    }
                }
            } catch (e: Exception) {
                Timber.w(e, "豆瓣想看加载下一页失败")
                _uiState.update {
                    it.copy(status = WishStatus.Error, errorMessage = e.friendlyMessage())
                }
            }
        }
    }

    /** 错误重试：首屏错误重新同步，尾部错误续翻下一页。 */
    fun retry() {
        val current = _uiState.value
        if (current.status !in listOf(WishStatus.Error, WishStatus.Empty)) return
        if (current.items.isEmpty()) {
            loadFirstPage()
        } else {
            _uiState.update { it.copy(status = WishStatus.Idle) }
            loadMore()
        }
    }

    private fun loadFirstPage() {
        syncJob?.cancel()
        val keepItems = _uiState.value.items
        _uiState.update {
            WishUiState(
                status = if (keepItems.isEmpty()) WishStatus.Loading else WishStatus.Refreshing,
                items = keepItems,
            )
        }
        syncJob = viewModelScope.launch {
            try {
                val page = doubanRepository.loadFirstPage(uid)
                val items = doubanRepository.items
                _uiState.update {
                    WishUiState(
                        items = items,
                        total = doubanRepository.totalCount,
                        // total=0 未必是真空表（私密账号 / 风控静默），文案在 Empty 视图给出隐私提示
                        status = when {
                            items.isEmpty() && page.total == 0 -> WishStatus.Empty
                            else -> WishStatus.Idle
                        },
                        hasMore = page.hasMore,
                        errorMessage = if (page.reachedPageLimit) "已达单次同步条数上限" else null,
                        lastSyncAt = doubanRepository.lastSyncAt,
                    )
                }
            } catch (e: Exception) {
                Timber.w(e, "豆瓣想看首页同步失败")
                _uiState.update {
                    WishUiState(
                        items = it.items,
                        status = if (it.items.isEmpty()) WishStatus.Error else WishStatus.Idle,
                        errorMessage = e.friendlyMessage(),
                    )
                }
            }
        }
    }

    private fun Exception.friendlyMessage(): String = when (this) {
        is DoubanApiException -> message
        else -> "网络异常：${message ?: "连接失败"}"
    }

    companion object {
        private const val SYNC_THROTTLE_MS = 30_000L
    }
}
