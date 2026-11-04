package com.mewbox.tv.ui.history

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.mewbox.tv.api.config.SettingsRepository
import com.mewbox.tv.bean.History
import com.mewbox.tv.domain.SiteRepository
import com.mewbox.tv.player.Players
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

data class HistoryUiState(
    val items: List<History> = emptyList(),
    val isLoading: Boolean = true,
)

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class HistoryViewModel @Inject constructor(
    private val repository: SiteRepository,
    private val settingsRepository: SettingsRepository,
) : ViewModel() {

    val uiState: StateFlow<HistoryUiState> = settingsRepository.configUrlFlow
        .flatMapLatest { configUrl ->
            repository.observeHistories(configUrl).map { list ->
                HistoryUiState(items = list, isLoading = false)
            }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), HistoryUiState())

    fun deleteHistory(key: String) {
        viewModelScope.launch {
            repository.deleteHistory(key)
        }
    }

    fun clearAll() {
        val currentUrl = settingsRepository.currentConfigUrl
        viewModelScope.launch {
            repository.clearHistories(currentUrl)
        }
    }

    companion object {
        fun formatProgress(position: Long, duration: Long): String {
            if (position <= 0L) return "刚刚观看"
            val posStr = Players.formatDuration(position)
            return if (duration > 0L) {
                val percent = ((position.toFloat() / duration) * 100).toInt().coerceIn(1, 100)
                val durStr = Players.formatDuration(duration)
                "已看 $percent% ($posStr / $durStr)"
            } else {
                "已看至 $posStr"
            }
        }
    }
}
