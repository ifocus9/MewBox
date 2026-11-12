package com.mewbox.tv.ui.web.fm

import android.app.Activity
import android.graphics.Color as AndroidColor
import android.net.Uri
import android.view.View
import android.view.ViewGroup
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.widget.FrameLayout
import com.mewbox.tv.util.AppToast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ContainedLoadingIndicator
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.mewbox.tv.bean.Config
import com.mewbox.tv.ui.setting.ConfigDialog
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * HTML 网页订阅源沉浸式全屏容器（对齐 MewPlayer WebTabFragment + WebHomeChromeController）：
 * - 全屏展示 WebView 并自动计算/注入 `--fm-safe-*` 安全区 CSS 变量与 `window.fm` / `window.fongmi` 能力
 * - 默认附带贴边蓝色悬浮条，点击展开「当前订阅胶囊 + 刷新按钮」，点击胶囊可查看或切换订阅
 */
@Composable
fun HtmlSourceScreen(
    onNavigateToPlay: (siteKey: String, vodId: String, vodName: String, vodPic: String) -> Unit,
    onNavigateToDetail: (siteKey: String, vodId: String, vodName: String, vodPic: String) -> Unit,
    onNavigateToSearch: (keyword: String) -> Unit,
    onNavigateToSettings: () -> Unit,
    viewModel: HtmlSourceViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val webState = uiState.webState
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    var showSubscriptionDialog by remember { mutableStateOf(false) }
    var showConfigDialog by remember { mutableStateOf(false) }
    var configEditMode by remember { mutableStateOf(false) }
    var configEditOldUrl by remember { mutableStateOf("") }
    var configInitialUrl by remember { mutableStateOf("") }
    var configInitialName by remember { mutableStateOf("") }

    var pendingFileCallback by remember { mutableStateOf<ValueCallback<Array<Uri>>?>(null) }
    val fileChooserLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val cb = pendingFileCallback
        pendingFileCallback = null
        val uris = WebChromeClient.FileChooserParams.parseResult(result.resultCode, result.data)
        cb?.onReceiveValue(uris)
    }

    // 收集 Toast 提示（使用应用内统一浅色 Toast，不受系统深色模式影响）
    LaunchedEffect(uiState.message) {
        val msg = uiState.message
        if (!msg.isNullOrBlank()) {
            AppToast.show(msg)
            viewModel.consumeMessage()
        }
    }

    // 收集来自 HomeWebBridge 的页面跳转与文件选择事件
    LaunchedEffect(viewModel.webController) {
        viewModel.webController.navEvents.collect { event ->
            when (event) {
                is HtmlWebNavEvent.NavigateToPlay -> {
                    onNavigateToPlay(event.siteKey, event.vodId, event.vodName, event.vodPic)
                }
                is HtmlWebNavEvent.NavigateToDetail -> {
                    onNavigateToDetail(event.siteKey, event.vodId, event.vodName, event.vodPic)
                }
                is HtmlWebNavEvent.NavigateToSearch -> {
                    onNavigateToSearch(event.keyword)
                }
                is HtmlWebNavEvent.OpenFileChooser -> {
                    pendingFileCallback?.onReceiveValue(null)
                    pendingFileCallback = event.callback
                    runCatching {
                        fileChooserLauncher.launch(event.intent)
                    }.onFailure {
                        pendingFileCallback = null
                        event.callback.onReceiveValue(null)
                    }
                }
            }
        }
    }

    // 监听前后台生命周期，向网页派发 fmpause / fmresume 事件
    DisposableEffect(lifecycleOwner, viewModel.webController) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> viewModel.webController.onScreenResume()
                Lifecycle.Event.ON_PAUSE -> viewModel.webController.onScreenPause()
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            viewModel.webController.bindWebViewContainer(null)
            viewModel.webController.bindFullscreenContainer(null)
        }
    }

    // 计算安全区像素（转换为 CSS px）并同步给网页
    val density = LocalDensity.current
    val layoutDirection = LocalLayoutDirection.current
    val statusTopPx = WindowInsets.statusBars.getTop(density)
    val navBottomPx = WindowInsets.navigationBars.getBottom(density)
    val cutoutTopPx = WindowInsets.displayCutout.getTop(density)
    val cutoutBottomPx = WindowInsets.displayCutout.getBottom(density)
    val cutoutLeftPx = WindowInsets.displayCutout.getLeft(density, layoutDirection)
    val cutoutRightPx = WindowInsets.displayCutout.getRight(density, layoutDirection)

    val safeTopCss = (max(statusTopPx, cutoutTopPx) / density.density).roundToInt()
    val safeBottomCss = (max(navBottomPx, cutoutBottomPx) / density.density).roundToInt()
    val safeLeftCss = (cutoutLeftPx / density.density).roundToInt()
    val safeRightCss = (cutoutRightPx / density.density).roundToInt()
    val bottomUiCss = if (webState.chromeOptions.bottomTabInset) 64 else 0

    LaunchedEffect(safeTopCss, safeRightCss, safeBottomCss, safeLeftCss, bottomUiCss) {
        viewModel.webController.updateViewport(
            WebHomeViewport(
                safeTopPx = safeTopCss,
                safeRightPx = safeRightCss,
                safeBottomPx = safeBottomCss,
                safeLeftPx = safeLeftCss,
                bottomUiInsetPx = bottomUiCss,
            )
        )
    }

    // 多级返回处理：全屏视频退出 -> 网页自定义返回处理器 -> WebView 后退 -> 双击退出应用
    var lastBackPressMs by remember { mutableLongStateOf(0L) }
    BackHandler(enabled = true) {
        viewModel.webController.handleBackPress(
            onFallback = {
                val now = System.currentTimeMillis()
                if (now - lastBackPressMs < 2_000L) {
                    (context as? Activity)?.finish()
                } else {
                    lastBackPressMs = now
                    AppToast.show("再按一次退出应用")
                }
            }
        )
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.White),
    ) {
        // 底层：沉浸式全屏 WebView 容器
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { ctx ->
                FrameLayout(ctx).apply {
                    layoutParams = ViewGroup.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT,
                    )
                    viewModel.webController.bindWebViewContainer(this)
                }
            },
            update = { container ->
                viewModel.webController.bindWebViewContainer(container)
            },
        )

        // 网页首屏或手动刷新加载中的居中提示卡片
        AnimatedVisibility(
            visible = webState.isInitialPageLoading && !webState.isFullscreenVideo,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier.fillMaxSize(),
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color(0xFFF7F8FA)),
                contentAlignment = Alignment.Center,
            ) {
                HtmlLoadingCard(subtitle = uiState.displaySubscriptionName)
            }
        }

        // 顶部 3dp 网页加载进度条
        AnimatedVisibility(
            visible = webState.isLoading && webState.progress in 1..99 && !webState.isFullscreenVideo,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier.align(Alignment.TopCenter),
        ) {
            LinearProgressIndicator(
                progress = { webState.progress / 100f },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(3.dp),
                color = Color(0xFF0A7AFF),
                trackColor = Color.Transparent,
            )
        }

        // 左侧悬浮控制条（放在加载卡片上方，即便远端网页响应慢也可随时点击切换订阅）
        HtmlSideFloatingBar(
            subscriptionName = uiState.displaySubscriptionName,
            visible = !webState.isFullscreenVideo && webState.chromeOptions.edgeHandle,
            showControls = webState.chromeOptions.floatingControls,
            onSubscriptionClick = { showSubscriptionDialog = true },
            onRefreshClick = { viewModel.reloadWebPage() },
        )

        // HTML5 视频全屏承载容器
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { ctx ->
                FrameLayout(ctx).apply {
                    setBackgroundColor(AndroidColor.BLACK)
                    visibility = View.GONE
                    viewModel.webController.bindFullscreenContainer(this)
                }
            },
            update = { container ->
                viewModel.webController.bindFullscreenContainer(container)
            },
        )
    }

    // 订阅查看与切换弹窗
    if (showSubscriptionDialog) {
        HtmlSubscriptionDialog(
            configs = uiState.configs,
            activeConfigUrl = uiState.activeConfig?.url.orEmpty(),
            onSelect = { url ->
                showSubscriptionDialog = false
                viewModel.switchSubscription(url)
            },
            onAdd = {
                showSubscriptionDialog = false
                configEditMode = false
                configEditOldUrl = ""
                configInitialUrl = ""
                configInitialName = ""
                showConfigDialog = true
            },
            onEdit = { item: Config ->
                showSubscriptionDialog = false
                configEditMode = true
                configEditOldUrl = item.url
                configInitialUrl = item.url
                configInitialName = item.name
                showConfigDialog = true
            },
            onDelete = { url ->
                viewModel.deleteConfig(url)
            },
            onOpenSettings = {
                showSubscriptionDialog = false
                onNavigateToSettings()
            },
            onDismiss = { showSubscriptionDialog = false },
        )
    }

    // 新增 / 编辑订阅弹窗
    if (showConfigDialog) {
        ConfigDialog(
            isEdit = configEditMode,
            initialUrl = configInitialUrl,
            initialName = configInitialName,
            onDismiss = { showConfigDialog = false },
            onConfirm = { url, name ->
                showConfigDialog = false
                viewModel.saveConfig(
                    url = url,
                    name = name,
                    isEdit = configEditMode,
                    oldUrl = configEditOldUrl,
                )
            },
        )
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun HtmlLoadingCard(subtitle: String, modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier.widthIn(min = 220.dp, max = 300.dp),
        shape = RoundedCornerShape(24.dp),
        color = Color.White,
        shadowElevation = 6.dp,
        border = BorderStroke(0.5.dp, Color(0x14000000)),
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 26.dp, vertical = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            ContainedLoadingIndicator(
                modifier = Modifier.size(44.dp),
                indicatorColor = MaterialTheme.colorScheme.primary,
                containerColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.12f),
            )
            Spacer(Modifier.height(16.dp))
            Text(
                text = "正在加载网页…",
                fontSize = 15.sp,
                fontWeight = FontWeight.Medium,
                color = Color(0xFF1A1A1A),
                textAlign = TextAlign.Center,
            )
            if (subtitle.isNotBlank()) {
                Spacer(Modifier.height(6.dp))
                Text(
                    text = subtitle,
                    fontSize = 12.5.sp,
                    color = Color(0xFF8A8F98),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    textAlign = TextAlign.Center,
                )
            }
        }
    }
}

