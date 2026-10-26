package com.mewbox.tv.ui.search

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.mewbox.tv.bean.Vod
import com.mewbox.tv.domain.SiteRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class SearchUiState(
    val keyword: String = "",
    val isSearching: Boolean = false,
    val results: List<Vod> = emptyList(),
    val history: List<String> = emptyList(),
)

@HiltViewModel
class SearchViewModel @Inject constructor(
    private val repository: SiteRepository,
) : ViewModel() {

    private val _uiState = MutableStateFlow(SearchUiState())
    val uiState: StateFlow<SearchUiState> = _uiState.asStateFlow()

    private var searchJob: Job? = null

    fun onKeywordChange(newKeyword: String) {
        _uiState.update { it.copy(keyword = newKeyword) }
    }

    fun search(keywordToSearch: String = _uiState.value.keyword) {
        val kw = keywordToSearch.trim()
        if (kw.isEmpty()) return

        // 加入搜索历史（去重前置）
        _uiState.update { state ->
            val newHistory = (listOf(kw) + state.history.filter { it != kw }).take(15)
            state.copy(keyword = kw, isSearching = true, results = emptyList(), history = newHistory)
        }

        searchJob?.cancel()
        searchJob = viewModelScope.launch {
            repository.searchAll(kw).collect { newVods ->
                _uiState.update { state ->
                    // 过滤相同 siteKey+vodId
                    val existingKeys = state.results.map { "${it.siteKey}_${it.vodId}" }.toSet()
                    val filtered = newVods.filter { "${it.siteKey}_${it.vodId}" !in existingKeys }
                    state.copy(results = state.results + filtered)
                }
            }
            _uiState.update { it.copy(isSearching = false) }
        }
    }

    fun clearHistory() {
        _uiState.update { it.copy(history = emptyList()) }
    }
}
