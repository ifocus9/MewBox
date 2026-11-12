package com.mewbox.tv.ui.setting

import com.mewbox.tv.util.AppToast
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.History
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.mewbox.tv.BuildConfig
import com.mewbox.tv.ui.components.LoadingState
import com.mewbox.tv.ui.components.SubscriptionHistoryDialog

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun SettingsScreen(
    modifier: Modifier = Modifier,
    /** 切换/新增订阅后立刻跳回首页：Node.js 加载 loading 由全局浮层展示在首页上 */
    onSwitchToHome: () -> Unit = {},
    viewModel: SettingsViewModel = hiltViewModel(),
) {
    val context = LocalContext.current
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    var showConfigDialog by rememberSaveable { mutableStateOf(false) }
    var configDialogIsEdit by rememberSaveable { mutableStateOf(false) }
    var configDialogInitialUrl by rememberSaveable { mutableStateOf("") }
    var configDialogInitialName by rememberSaveable { mutableStateOf("") }
    var showHistoryDialog by rememberSaveable { mutableStateOf(false) }
    var showAboutDialog by rememberSaveable { mutableStateOf(false) }
    var showTmdbDialog by rememberSaveable { mutableStateOf(false) }
    var showDoubanDialog by rememberSaveable { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        viewModel.loadCacheSize()
    }

    // 成功 / 失败的统一提示（订阅弹窗点确定后立即关闭，结果只走 Toast）
    LaunchedEffect(state.message) {
        state.message?.let { msg ->
            AppToast.show(msg)
            viewModel.consumeMessage()
        }
    }

    val currentConfig = state.configs.find { it.url == state.savedConfigUrl }
    // 未设置订阅名称时，直接以订阅链接作为名称展示
    val subscriptionDisplay = when {
        currentConfig != null && currentConfig.name.isNotBlank() -> currentConfig.name
        currentConfig != null -> formatMiddleEllipsis(currentConfig.url)
        state.savedConfigUrl.isNotBlank() -> formatMiddleEllipsis(state.savedConfigUrl)
        else -> "未配置"
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
    ) {
        // 顶部标题栏（参考 MewPlayer fragment_setting MaterialToolbar）
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .statusBarsPadding()
                .height(56.dp)
                .padding(horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "设置",
                fontSize = 20.sp,
                fontWeight = FontWeight.Bold,
                color = TextPrimary,
            )
        }

        // 内容区域（参考 MewPlayer fragment_setting）
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 150.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            // 卡片 1：订阅配置（点击新增弹窗，长按编辑弹窗并可设置名称；右侧历史图标为灰色）
            SettingCard {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(56.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    // 主点击区域：点击新增，长按编辑
                    Row(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxHeight()
                            .combinedClickable(
                                onClick = {
                                    configDialogIsEdit = false
                                    configDialogInitialUrl = ""
                                    configDialogInitialName = ""
                                    showConfigDialog = true
                                },
                                onLongClick = {
                                    configDialogIsEdit = true
                                    configDialogInitialUrl = state.savedConfigUrl
                                    configDialogInitialName = currentConfig?.name.orEmpty()
                                    showConfigDialog = true
                                },
                            )
                            .padding(start = 16.dp, end = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = "订阅",
                            fontSize = 16.sp,
                            color = TextPrimary,
                            fontWeight = FontWeight.Normal,
                        )
                        Spacer(Modifier.weight(1f))
                        Text(
                            text = subscriptionDisplay,
                            fontSize = 14.sp,
                            color = TextSecondary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            textAlign = TextAlign.End,
                            modifier = Modifier.widthIn(max = 180.dp),
                        )
                    }

                    // 历史订阅列表按钮
                    IconButton(
                        onClick = {
                            if (state.configs.any { it.url != state.savedConfigUrl }) {
                                showHistoryDialog = true
                            } else {
                                AppToast.show("没有可切换的订阅")
                            }
                        },
                        modifier = Modifier
                            .padding(end = 8.dp)
                            .size(40.dp),
                    ) {
                        Icon(
                            imageVector = Icons.Default.History,
                            contentDescription = "订阅列表",
                            modifier = Modifier.size(22.dp),
                            tint = TextSecondary,
                        )
                    }
                }
            }

            // 卡片 2：TMDB 设置
            SettingCard {
                SettingRow(
                    title = "TMDB 设置",
                    value = if (state.tmdbConfig.enable) "开启" else "关闭",
                    onClick = { showTmdbDialog = true },
                )
            }

            // 卡片 3：豆瓣想看（追剧页「豆瓣想看」分段的账号来源）
            SettingCard {
                SettingRow(
                    title = "豆瓣想看",
                    value = state.doubanUid.ifBlank { "未设置" },
                    onClick = { showDoubanDialog = true },
                )
            }

            // 卡片 3：缓存清理
            SettingCard {
                SettingRow(
                    title = "缓存",
                    value = state.cacheSize,
                    onClick = { viewModel.clearCache() },
                )
            }

            // 卡片 4：关于
            SettingCard {
                SettingRow(
                    title = "关于",
                    value = "MewBox ${BuildConfig.VERSION_NAME}",
                    onClick = { showAboutDialog = true },
                )
            }
        }
    }

    // 历史订阅弹窗
    if (showHistoryDialog) {
        SubscriptionHistoryDialog(
            configs = state.configs.filter { it.url != state.savedConfigUrl },
            onSelect = { url ->
                viewModel.import(url)
                showHistoryDialog = false
            },
            onEdit = { item ->
                showHistoryDialog = false
                configDialogIsEdit = true
                configDialogInitialUrl = item.url
                configDialogInitialName = item.name
                showConfigDialog = true
            },
            onDelete = { url ->
                viewModel.deleteConfig(url)
            },
            onDismiss = { showHistoryDialog = false },
        )
    }

    // 添加 / 编辑订阅弹窗
    if (showConfigDialog) {
        ConfigDialog(
            isEdit = configDialogIsEdit,
            initialUrl = configDialogInitialUrl,
            initialName = configDialogInitialName,
            onDismiss = {
                viewModel.consumeMessage()
                showConfigDialog = false
            },
            onConfirm = { url, name ->
                showConfigDialog = false
                viewModel.saveConfig(
                    url = url,
                    name = name,
                    isEdit = configDialogIsEdit,
                    oldUrl = configDialogInitialUrl,
                )
            },
        )
    }

    // TMDB 设置弹窗
    if (showTmdbDialog) {
        TmdbDialog(
            initialConfig = state.tmdbConfig,
            onTestApi = { key, base -> viewModel.testTmdbApi(key, base) },
            onTestImage = { base -> viewModel.testTmdbImage(base) },
            onSave = { config ->
                viewModel.saveTmdbConfig(config)
                showTmdbDialog = false
            },
            onDismiss = { showTmdbDialog = false },
        )
    }

    // 豆瓣想看 uid 弹窗
    if (showDoubanDialog) {
        DoubanDialog(
            initialUid = state.doubanUid,
            onSave = { uid ->
                viewModel.saveDoubanUid(uid)
                showDoubanDialog = false
            },
            onDismiss = { showDoubanDialog = false },
        )
    }

    // 关于弹窗
    if (showAboutDialog) {
        AboutDialog(
            onDismiss = { showAboutDialog = false },
        )
    }

    // 导入成功自动跳回首页
    LaunchedEffect(state.pendingHomeNav) {
        if (state.pendingHomeNav) {
            onSwitchToHome()
            viewModel.consumePendingHomeNav()
        }
    }
}
