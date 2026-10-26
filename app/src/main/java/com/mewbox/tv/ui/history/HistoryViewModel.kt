package com.mewbox.tv.ui.history

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.mewbox.tv.bean.History
import com.mewbox.tv.domain.SiteRepository
import com.mewbox.tv.player.Players
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class HistoryUiState(
    val items: List<History> = emptyList(),
    val isLoading: Boolean = true,
)

@HiltViewModel
class HistoryViewModel @Inject constructor(
    private val repository: SiteRepository,
) : ViewModel() {

    private val _uiState = MutableStateFlow(HistoryUiState())
    val uiState: StateFlow<HistoryUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            repository.observeHistories().collect { list ->
                _uiState.update { it.copy(items = list, isLoading = false) }
            }
        }
    }

    fun deleteHistory(key: String) {
        viewModelScope.launch {
            repository.deleteHistory(key)
        }
    }

    fun clearAll() {
        viewModelScope.launch {
            repository.clearHistories()
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
