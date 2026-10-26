package com.mewbox.tv.ui.setting

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.mewbox.tv.api.config.SettingsRepository
import com.mewbox.tv.api.config.VodConfig
import com.mewbox.tv.bean.Site
import com.mewbox.tv.catvod.SpiderRouter
import com.mewbox.tv.db.SiteDao
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class SiteItem(val site: Site, val supported: Boolean)

data class SettingsUiState(
    val sites: List<SiteItem> = emptyList(),
    val savedHost: String = "",
    val savedConfigUrl: String = "",
    val importing: Boolean = false,
    val message: String? = null,
)

@HiltViewModel
class SettingsViewModel @Inject constructor(
    siteDao: SiteDao,
    private val vodConfig: VodConfig,
    private val settings: SettingsRepository,
    private val router: SpiderRouter,
) : ViewModel() {

    private data class Transient(val importing: Boolean = false, val message: String? = null)

    private val transient = MutableStateFlow(Transient())

    val uiState: StateFlow<SettingsUiState> = combine(
        siteDao.observeAll(),
        settings.hostMappingFlow,
        settings.configUrlFlow,
        transient,
    ) { sites, host, url, t ->
        SettingsUiState(
            sites = sites.filter { !it.hide }.map { SiteItem(it, router.isSupported(it)) },
            savedHost = host,
            savedConfigUrl = url,
            importing = t.importing,
            message = t.message,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SettingsUiState())

    fun import(input: String) {
        if (transient.value.importing) return
        viewModelScope.launch {
            transient.value = Transient(importing = true)
            transient.value = try {
                Transient(message = "导入成功：${vodConfig.import(input)} 个站点")
            } catch (e: Exception) {
                Transient(message = "导入失败：${e.message ?: e.javaClass.simpleName}")
            }
        }
    }

    fun saveHost(value: String) {
        viewModelScope.launch {
            settings.setHostMapping(value)
            transient.update { it.copy(message = if (value.isBlank()) "已清除地址映射" else "已保存映射：$value") }
        }
    }

    fun toggleEnabled(site: Site) = viewModelScope.launch { vodConfig.update(site.copy(enabled = !site.enabled)) }
    fun toggleSearchable(site: Site) = viewModelScope.launch { vodConfig.update(site.copy(searchable = !site.searchable)) }
    fun toggleChangeable(site: Site) = viewModelScope.launch { vodConfig.update(site.copy(changeable = !site.changeable)) }
    fun setHome(site: Site) = viewModelScope.launch { vodConfig.setHome(site.key) }
    fun consumeMessage() = transient.update { it.copy(message = null) }
}
