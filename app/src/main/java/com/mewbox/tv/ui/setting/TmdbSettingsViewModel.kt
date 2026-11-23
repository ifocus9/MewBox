package com.mewbox.tv.ui.setting

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.mewbox.tv.api.tmdb.TmdbClient
import com.mewbox.tv.api.tmdb.TmdbRepository
import com.mewbox.tv.bean.TmdbConfig
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

sealed interface TmdbTestStatus {
    data object Idle : TmdbTestStatus
    data object Testing : TmdbTestStatus
    data class Success(val message: String) : TmdbTestStatus
    data class Error(val message: String) : TmdbTestStatus
}

data class TmdbSettingsUiState(
    val loaded: Boolean = false,
    val enable: Boolean = false,
    val apiKey: String = "",
    val apiBase: String = "",
    val imageBase: String = "",
    val savedConfig: TmdbConfig = TmdbConfig(),
    val apiTest: TmdbTestStatus = TmdbTestStatus.Idle,
    val imageTest: TmdbTestStatus = TmdbTestStatus.Idle,
    val saving: Boolean = false,
    val savedAndExit: Boolean = false,
    val message: String? = null,
) {
    val hasChanges: Boolean
        get() = loaded && (
            enable != savedConfig.enable ||
                apiKey.trim() != savedConfig.apiKey.trim() ||
                apiBase.trim() != savedConfig.apiBase.trim() ||
                imageBase.trim() != savedConfig.imageBase.trim()
            )
}

@HiltViewModel
class TmdbSettingsViewModel @Inject constructor(
    private val tmdbRepository: TmdbRepository,
    private val tmdbClient: TmdbClient,
) : ViewModel() {

    private val _uiState = MutableStateFlow(TmdbSettingsUiState())
    val uiState: StateFlow<TmdbSettingsUiState> = _uiState.asStateFlow()

    private var apiTestJob: Job? = null
    private var imageTestJob: Job? = null

    init {
        viewModelScope.launch {
            val config = tmdbRepository.getTmdbConfig()
            _uiState.update {
                it.copy(
                    loaded = true,
                    enable = config.enable,
                    apiKey = config.apiKey,
                    apiBase = config.apiBase,
                    imageBase = config.imageBase,
                    savedConfig = config,
                )
            }
        }
    }

    fun onEnableChange(enable: Boolean) {
        _uiState.update { it.copy(enable = enable) }
    }

    fun onApiKeyChange(value: String) {
        apiTestJob?.cancel()
        _uiState.update { it.copy(apiKey = value, apiTest = TmdbTestStatus.Idle) }
    }

    fun onApiBaseChange(value: String) {
        apiTestJob?.cancel()
        _uiState.update { it.copy(apiBase = value, apiTest = TmdbTestStatus.Idle) }
    }

    fun onImageBaseChange(value: String) {
        imageTestJob?.cancel()
        _uiState.update { it.copy(imageBase = value, imageTest = TmdbTestStatus.Idle) }
    }

    fun testApi() {
        val snapshot = _uiState.value
        if (snapshot.apiTest is TmdbTestStatus.Testing) return
        val key = snapshot.apiKey.trim()
        if (key.isEmpty()) {
            _uiState.update {
                it.copy(apiTest = TmdbTestStatus.Error("请先输入 API Key 或 Token"))
            }
            return
        }
        val base = snapshot.apiBase.trim()
        _uiState.update { it.copy(apiTest = TmdbTestStatus.Testing) }
        apiTestJob = viewModelScope.launch {
            val result = tmdbClient.testConnectivity(key, base)
            _uiState.update { state ->
                val status = result.fold(
                    onSuccess = { msg -> TmdbTestStatus.Success(msg) },
                    onFailure = { err -> TmdbTestStatus.Error(err.message ?: "连接失败") },
                )
                state.copy(apiTest = status)
            }
        }
    }

    fun testImage() {
        val snapshot = _uiState.value
        if (snapshot.imageTest is TmdbTestStatus.Testing) return
        val base = snapshot.imageBase.trim()
        _uiState.update { it.copy(imageTest = TmdbTestStatus.Testing) }
        imageTestJob = viewModelScope.launch {
            val result = tmdbClient.testImageCdn(base)
            _uiState.update { state ->
                val status = result.fold(
                    onSuccess = { msg -> TmdbTestStatus.Success(msg) },
                    onFailure = { err -> TmdbTestStatus.Error(err.message ?: "图片 CDN 连接失败") },
                )
                state.copy(imageTest = status)
            }
        }
    }

    fun save() {
        val snapshot = _uiState.value
        if (snapshot.saving) return
        val config = TmdbConfig(
            enable = snapshot.enable,
            apiKey = snapshot.apiKey.trim(),
            apiBase = snapshot.apiBase.trim(),
            imageBase = snapshot.imageBase.trim(),
        )
        _uiState.update { it.copy(saving = true) }
        viewModelScope.launch {
            tmdbRepository.save(config)
            _uiState.update {
                it.copy(
                    enable = config.enable,
                    apiKey = config.apiKey,
                    apiBase = config.apiBase,
                    imageBase = config.imageBase,
                    savedConfig = config,
                    saving = false,
                    savedAndExit = true,
                    message = "TMDB 设置已保存",
                )
            }
        }
    }

    fun consumeMessage() {
        _uiState.update { it.copy(message = null) }
    }

    fun consumeSavedAndExit() {
        _uiState.update { it.copy(savedAndExit = false) }
    }
}
