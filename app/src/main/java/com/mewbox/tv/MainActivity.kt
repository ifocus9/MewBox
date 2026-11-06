package com.mewbox.tv

import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.VideoLibrary
import androidx.compose.material3.ContainedLoadingIndicator
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.ContentDrawScope
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import androidx.navigation.navDeepLink
import com.kyant.backdrop.backdrops.LayerBackdrop
import com.kyant.backdrop.backdrops.layerBackdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import com.mewbox.tv.bean.Vod
import com.mewbox.tv.domain.SiteRepository
import com.mewbox.tv.engine.nodejs.NodeBootStage
import com.mewbox.tv.engine.nodejs.NodeRuntime
import com.mewbox.tv.ui.detail.DetailScreen
import com.mewbox.tv.ui.detail.DirectPlayScreen
import com.mewbox.tv.ui.history.HistoryScreen
import com.mewbox.tv.ui.home.HomeScreen
import com.mewbox.tv.ui.navbar.FloatingNavBar
import com.mewbox.tv.ui.navbar.GlassTabItem
import com.mewbox.tv.ui.navbar.NavAxis
import com.mewbox.tv.ui.navbar.NavMetrics
import com.mewbox.tv.ui.search.SearchScreen
import com.mewbox.tv.ui.setting.SettingsScreen
import com.mewbox.tv.ui.theme.LiquidGlassDefaults
import com.mewbox.tv.ui.theme.MewBoxTheme
import dagger.hilt.android.AndroidEntryPoint
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import timber.log.Timber
import javax.inject.Inject

private enum class Tab(val label: String, val icon: ImageVector) {
    Home("首页", Icons.Rounded.Home),
    Keep("追剧", Icons.Rounded.VideoLibrary),
    Settings("设置", Icons.Rounded.Settings),
}

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    @Inject lateinit var nodeRuntime: NodeRuntime
    @Inject lateinit var siteRepository: SiteRepository

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // 强制不受系统深色模式控制：透明状态栏/导航栏 + 深色系统状态栏图标（适配白底）
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.light(
                android.graphics.Color.TRANSPARENT,
                android.graphics.Color.TRANSPARENT,
            ),
            navigationBarStyle = SystemBarStyle.light(
                android.graphics.Color.TRANSPARENT,
                android.graphics.Color.TRANSPARENT,
            ),
        )
        setContent {
            MewBoxTheme(darkTheme = false) {
                MainScreen(nodeRuntime = nodeRuntime, onSiteAction = ::runSiteAction)
            }
        }
    }

    /**
     * 执行站点动作（baseset 网盘配置 / 扫码等）：不进入详情页，直接交给站点 spider 处理。
     *
     * 放在 Activity 层执行，而不是为这一个动作去创建 Activity 作用域的 HomeViewModel
     * —— 那会把它 init 里的整套首页管线（ensureActiveConfig / DB 观察 / loadHome）多跑一遍，
     * 造成冷启动双份请求、订阅重复导入，且动作返回的 msg 也没人收集。
     */
    private fun runSiteAction(siteKey: String, action: String) {
        lifecycleScope.launch {
            val site = siteRepository.getSite(siteKey)
            if (site == null) {
                Toast.makeText(this@MainActivity, "站点不存在或已删除", Toast.LENGTH_SHORT).show()
                return@launch
            }
            val result = siteRepository.action(site, action)
            if (result.msg.isNotBlank()) {
                Toast.makeText(this@MainActivity, result.msg, Toast.LENGTH_SHORT).show()
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        if (isFinishing) {
            nodeRuntime.clear()
        }
    }
}

@Composable
private fun MainScreen(
    nodeRuntime: NodeRuntime,
    onSiteAction: (siteKey: String, action: String) -> Unit,
) {
    val nav = rememberNavController()
    val context = LocalContext.current

    // Node.js 源的加载进度：全程只保留一个提示，文案原地更新，避免连续 Toast 闪动
    val nodeLoadingText by nodeRuntime.loading.collectAsStateWithLifecycle()
    LaunchedEffect(nodeRuntime) {
        nodeRuntime.results.collect { event ->
            val duration = if (event.stage == NodeBootStage.FAILED) Toast.LENGTH_LONG else Toast.LENGTH_SHORT
            Toast.makeText(context, event.message, duration).show()
        }
    }

    // Node.js 加载浮层展示期间：系统返回打断后台加载（远端订阅卡住时可手动中断换源），不触发页面级返回
    BackHandler(enabled = nodeLoadingText != null) {
        nodeRuntime.cancelLoading()
        Toast.makeText(context, "已打断加载，可切换其他订阅源", Toast.LENGTH_SHORT).show()
    }

    Box(modifier = Modifier.fillMaxSize()) {
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = MaterialTheme.colorScheme.background,
        ) {
            val navigateToSearch: (String) -> Unit = { keyword ->
                if (keyword.isBlank()) {
                    nav.navigate("search")
                } else {
                    nav.navigate("search?keyword=${Uri.encode(keyword)}")
                }
            }

            val navigateToDetail: (String, String, String, String) -> Unit = { siteKey, vodId, vodName, vodPic ->
                // 对齐 FongMi 逻辑：若条目为配置/动作（如 baseset 网盘配置/扫码），直接执行站点动作打开 Webview，绝不进入海报详情页
                if (Vod.isActionId(vodId)) {
                    Timber.i("条目属于配置动作，直接触发站点动作拦截海报详情页: siteKey=$siteKey, vodId=$vodId")
                    onSiteAction(siteKey, vodId)
                } else if (vodId.contains("msearch", ignoreCase = true)) {
                    Timber.i("条目包含 msearch，直接触发跨源搜索: siteKey=$siteKey, vodId=$vodId, vodName=$vodName")
                    navigateToSearch(vodName)
                } else {
                    Timber.i("导航至详情: siteKey=$siteKey, vodId=$vodId, vodName=$vodName, vodPic=$vodPic")
                    nav.navigate("detail/$siteKey?vodId=${Uri.encode(vodId)}&vodName=${Uri.encode(vodName)}&vodPic=${Uri.encode(vodPic)}")
                }
            }

            val navigateToPlayFromHistory: (String, String, String, String) -> Unit = { siteKey, vodId, vodName, vodPic ->
                if (Vod.isActionId(vodId)) {
                    Timber.i("历史记录条目属于配置动作，直接触发站点动作: siteKey=$siteKey, vodId=$vodId")
                    onSiteAction(siteKey, vodId)
                } else if (vodId.contains("msearch", ignoreCase = true)) {
                    Timber.i("历史记录条目包含 msearch，直接触发跨源搜索: siteKey=$siteKey, vodId=$vodId, vodName=$vodName")
                    navigateToSearch(vodName)
                } else {
                    Timber.i("从历史记录直接起播: siteKey=$siteKey, vodId=$vodId, vodName=$vodName, vodPic=$vodPic")
                    nav.navigate("play/$siteKey?vodId=${Uri.encode(vodId)}&vodName=${Uri.encode(vodName)}&vodPic=${Uri.encode(vodPic)}")
                }
            }

            NavHost(
                navController = nav,
                startDestination = "main",
                modifier = Modifier.fillMaxSize(),
            ) {
                composable(
                    route = "main",
                    exitTransition = {
                        if (targetState.destination.route?.startsWith("play") == true) {
                            ExitTransition.None
                        } else null
                    },
                    popEnterTransition = {
                        if (initialState.destination.route?.startsWith("play") == true) {
                            EnterTransition.None
                        } else null
                    },
                ) {
                    MainTabScreen(
                        onNavigateToDetail = navigateToDetail,
                        onNavigateToSearch = navigateToSearch,
                        onNavigateToPlayFromHistory = navigateToPlayFromHistory,
                    )
                }
                composable(
                    route = "search?keyword={keyword}",
                    arguments = listOf(
                        navArgument("keyword") {
                            type = NavType.StringType
                            defaultValue = ""
                        },
                    ),
                    deepLinks = listOf(
                        navDeepLink { uriPattern = "mewbox://search?keyword={keyword}" },
                        navDeepLink { uriPattern = "mewbox://search" },
                    ),
                ) {
                    SearchScreen(
                        onNavigateToDetail = navigateToDetail,
                        onBack = { nav.popBackStack() },
                    )
                }
                composable(
                    route = "detail/{siteKey}?vodId={vodId}&vodName={vodName}&vodPic={vodPic}",
                    arguments = listOf(
                        navArgument("siteKey") { type = NavType.StringType },
                        navArgument("vodId") {
                            type = NavType.StringType
                            defaultValue = ""
                        },
                        navArgument("vodName") {
                            type = NavType.StringType
                            defaultValue = ""
                        },
                        navArgument("vodPic") {
                            type = NavType.StringType
                            defaultValue = ""
                        },
                    ),
                ) {
                    DetailScreen(
                        onBack = { nav.popBackStack() },
                    )
                }
                composable(
                    route = "play/{siteKey}?vodId={vodId}&vodName={vodName}&vodPic={vodPic}",
                    arguments = listOf(
                        navArgument("siteKey") { type = NavType.StringType },
                        navArgument("vodId") {
                            type = NavType.StringType
                            defaultValue = ""
                        },
                        navArgument("vodName") {
                            type = NavType.StringType
                            defaultValue = ""
                        },
                        navArgument("vodPic") {
                            type = NavType.StringType
                            defaultValue = ""
                        },
                        navArgument("autoPlay") {
                            type = NavType.BoolType
                            defaultValue = true
                        },
                    ),
                    enterTransition = { fadeIn(tween(150)) },
                    exitTransition = { ExitTransition.None },
                    popEnterTransition = { EnterTransition.None },
                    popExitTransition = { ExitTransition.None },
                ) {
                    DirectPlayScreen(
                        onBack = { nav.popBackStack() },
                    )
                }
            }
        }

        // Node.js 下载 / 启动进度：轻压暗遮罩 + 居中白色圆角卡片，盖在整个界面之上（含底部 Tab）
        // 遮罩吞掉触摸：资源包替换 / 服务重启期间界面处于半可用状态，避免误操作
        NodeLoadingOverlay(text = nodeLoadingText)
    }
}

/**
 * Node.js 加载浮层：轻压暗遮罩 + 居中白色圆角卡片。
 * 遮罩吞掉触摸，避免资源包替换 / 服务重启期间发生误操作。
 */
@Composable
private fun NodeLoadingOverlay(text: String?, modifier: Modifier = Modifier) {
    // 退场动画期间 text 已变 null，用上一次的文案兜住，避免卡片文字闪空
    var lastText by remember { mutableStateOf("") }
    LaunchedEffect(text) {
        text?.let { lastText = it }
    }
    val cardScale by animateFloatAsState(
        targetValue = if (text != null) 1f else 0.92f,
        animationSpec = tween(durationMillis = 220, easing = FastOutSlowInEasing),
        label = "nodeLoadingCardScale",
    )
    AnimatedVisibility(
        visible = text != null,
        enter = fadeIn(tween(180)),
        exit = fadeOut(tween(160)),
        modifier = modifier.fillMaxSize(),
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color(0xFF0B1220).copy(alpha = 0.26f))
                .pointerInput(Unit) { detectTapGestures { } },
            contentAlignment = Alignment.Center,
        ) {
            NodeLoadingCard(
                text = text ?: lastText,
                modifier = Modifier.graphicsLayer {
                    scaleX = cardScale
                    scaleY = cardScale
                },
            )
        }
    }
}

/**
 * Node.js 加载卡片：资源包下载 / 服务启动期间盖在内容之上。
 * 比裸露的整屏转圈更有"在执行一件事"的观感，副标题顺带解释当前这一步在干什么。
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun NodeLoadingCard(text: String, modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier.widthIn(min = 220.dp, max = 300.dp),
        shape = RoundedCornerShape(24.dp),
        color = Color.White,
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
                text = text,
                fontSize = 15.sp,
                fontWeight = FontWeight.Medium,
                color = Color(0xFF1A1A1A),
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(6.dp))
            Text(
                text = nodeLoadingHint(text),
                fontSize = 12.5.sp,
                color = Color(0xFF8A8F98),
                textAlign = TextAlign.Center,
            )
        }
    }
}

/** 按当前阶段给一句解释，让用户知道在等什么 */
private fun nodeLoadingHint(text: String): String = when {
    text.contains("下载") -> "正在下载资源包"
    text.contains("重启") -> "正在应用新版本并重启服务"
    text.contains("启动") -> "正在启动本地服务"
    else -> "请稍候"
}

/**
 * 主页面多 Tab 容器（参考 AVBox 实现高流畅度的液态玻璃悬浮导航与横向页面联动）
 */
@Composable
private fun MainTabScreen(
    onNavigateToDetail: (String, String, String, String) -> Unit,
    onNavigateToSearch: (String) -> Unit,
    onNavigateToPlayFromHistory: (String, String, String, String) -> Unit,
) {
    val pagerState = rememberPagerState(pageCount = { Tab.entries.size })
    val scope = rememberCoroutineScope()

    // 预热 pager 布局与着色器（参考 AVBox 抹平初次点击/滑动的卡顿）
    LaunchedEffect(pagerState) {
        withFrameNanos { }
        withFrameNanos { }
        pagerState.scrollBy(1f)
        pagerState.scrollBy(-1f)
    }

    // 当不在首页时按返回键平滑切回发现首页
    BackHandler(enabled = pagerState.currentPage != 0) {
        scope.launch { pagerState.animateScrollToPage(0) }
    }

    val liquidGlassConfig = LiquidGlassDefaults.Config
    val liquidGlassEnabled = liquidGlassConfig.navbarEnabled && LiquidGlassDefaults.isSupported
    val liquidBackdropBgColor = MaterialTheme.colorScheme.surfaceContainer
    val liquidBackdropOnDraw: ContentDrawScope.() -> Unit =
        remember(liquidBackdropBgColor) {
            { drawRect(liquidBackdropBgColor); drawContent() }
        }
    val liquidBackdrop = rememberLayerBackdrop(onDraw = liquidBackdropOnDraw)
    val density = LocalDensity.current
    val navBandExtent = NavMetrics.BAND_EXTENT_DP.dp
    val liquidBackdropBounds: (Size) -> Rect? = remember(density, navBandExtent) {
        { size ->
            val extentPx = with(density) { navBandExtent.toPx() }
            Rect(0f, size.height - extentPx, size.width, size.height)
        }
    }

    // 关键优化（对齐 AVBox）：在滑动翻页或切 Tab 动画期间暂停 Backdrop 图层重录制，避免满载掉帧
    val pauseGlassRecording: () -> Boolean = remember(pagerState) {
        { pagerState.isScrollInProgress }
    }

    val selectTab: (Int) -> Unit = { index ->
        scope.launch {
            pagerState.animateScrollToPage(index)
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .then(
                        if (liquidGlassEnabled) {
                            Modifier.layerBackdrop(
                                backdrop = liquidBackdrop,
                                recordBounds = liquidBackdropBounds,
                                pauseRecording = pauseGlassRecording,
                            )
                        } else {
                            Modifier
                        }
                    ),
            ) {
                HorizontalPager(
                    state = pagerState,
                    beyondViewportPageCount = 2,
                    modifier = Modifier.fillMaxSize(),
                ) { page ->
                    when (Tab.entries[page]) {
                        Tab.Home -> HomeScreen(
                            onNavigateToDetail = onNavigateToDetail,
                            onNavigateToSearch = onNavigateToSearch,
                        )
                        Tab.Keep -> HistoryScreen(
                            onNavigateToPlay = onNavigateToPlayFromHistory,
                        )
                        Tab.Settings -> SettingsScreen(
                            // 切换订阅源后跳回首页：Node.js 加载 loading 浮层展示在首页上
                            onSwitchToHome = {
                                scope.launch { pagerState.animateScrollToPage(0) }
                            },
                        )
                    }
                }
            }

            if (liquidGlassEnabled) {
                val scrimColor = MaterialTheme.colorScheme.surfaceContainer.copy(alpha = 0.9f)
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(navBandExtent)
                        .align(Alignment.BottomCenter)
                        .background(Brush.verticalGradient(0f to Color.Transparent, 1f to scrimColor)),
                )
            }

            // 悬浮底部导航栏（自适应：支持设备展示 AVBox 真液态玻璃效果，雷神模拟器等展示方案 A 拟态流光胶囊）
            AdaptiveFloatingNavBar(
                liquidGlassEnabled = liquidGlassEnabled,
                liquidBackdrop = liquidBackdrop,
                tabs = Tab.entries,
                selectedTabIndex = pagerState.targetPage,
                onSelectTab = selectTab,
                modifier = Modifier.align(Alignment.BottomCenter),
            )
        }
    }

/**
 * 自适应悬浮底部导航栏：
 * - Android 12+ 真机：运行底层 :libs:backdrop 真实液态玻璃与折射
 * - Android 9 模拟器 / 低版本：运行方案 A 拟态玻璃（双色菲涅尔渐变描边 + 弹性滑动胶囊指示器）
 */
@Composable
private fun AdaptiveFloatingNavBar(
    liquidGlassEnabled: Boolean,
    liquidBackdrop: LayerBackdrop?,
    tabs: List<Tab>,
    selectedTabIndex: Int,
    onSelectTab: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (liquidGlassEnabled && liquidBackdrop != null) {
        val glassTabs = remember(tabs) {
            tabs.map { tab -> GlassTabItem(icon = tab.icon, label = tab.label) }
        }
        Box(
            modifier = modifier
                .widthIn(max = 320.dp)
                .fillMaxWidth()
                .windowInsetsPadding(WindowInsets.navigationBars)
                .padding(horizontal = 16.dp)
                .padding(bottom = NavMetrics.MARGIN_DP.dp),
        ) {
            FloatingNavBar(
                backdrop = liquidBackdrop,
                axis = NavAxis.Horizontal,
                selectedTabIndex = { selectedTabIndex },
                onTabSelected = onSelectTab,
                tabs = glassTabs,
                config = LiquidGlassDefaults.Config,
                interactive = { true },
            )
        }
    } else {
        Box(
            modifier = modifier
                .windowInsetsPadding(WindowInsets.navigationBars),
        ) {
            MewFloatingBottomBar(
                tabs = tabs,
                selectedTabIndex = selectedTabIndex,
                onSelectTab = onSelectTab,
            )
        }
    }
}

/**
 * 方案 A 拟态玻璃悬浮导航胶囊组件（全版本通用，雷神模拟器 Android 9 与低版本专属高质感呈现）
 * - 28dp 圆角半透明亚克力卡片
 * - 双色菲涅尔顶部高光边缘描边 + 微弱白色漫反射
 * - 选中的 Tab 胶囊滑块采用 Spring 阻尼弹性位移，模拟流体质感
 */
@Composable
private fun MewFloatingBottomBar(
    tabs: List<Tab>,
    selectedTabIndex: Int,
    onSelectTab: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val shape = RoundedCornerShape(28.dp)
    Surface(
        shape = shape,
        color = Color.White.copy(alpha = 0.85f),
        shadowElevation = 8.dp,
        border = BorderStroke(
            0.75.dp,
            Brush.verticalGradient(
                listOf(
                    Color.White.copy(alpha = 0.85f),
                    Color.White.copy(alpha = 0.2f),
                ),
            ),
        ),
        modifier = modifier
            .padding(horizontal = 24.dp, vertical = 18.dp)
            .height(56.dp)
            .widthIn(max = 280.dp),
    ) {
        Box(
            modifier = Modifier
                .background(
                    Brush.verticalGradient(
                        0f to Color.White.copy(alpha = 0.4f),
                        0.5f to Color.Transparent,
                    ),
                )
                .padding(4.dp),
        ) {
            BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
                val tabWidth = maxWidth / tabs.size
                val indicatorOffset by animateDpAsState(
                    targetValue = tabWidth * selectedTabIndex,
                    animationSpec = spring(
                        dampingRatio = Spring.DampingRatioLowBouncy,
                        stiffness = Spring.StiffnessMediumLow,
                    ),
                    label = "TabIndicatorOffset",
                )

                // 拟态流体滑动胶囊指示器
                Box(
                    modifier = Modifier
                        .offset(x = indicatorOffset)
                        .width(tabWidth)
                        .fillMaxHeight()
                        .padding(2.dp)
                        .clip(RoundedCornerShape(22.dp))
                        .background(Color(0x14000000))
                        .border(
                            width = 0.5.dp,
                            color = Color.White.copy(alpha = 0.6f),
                            shape = RoundedCornerShape(22.dp),
                        ),
                )

                Row(
                    modifier = Modifier.fillMaxSize(),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    tabs.forEachIndexed { index, tab ->
                        val isSelected = selectedTabIndex == index
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxHeight()
                                .clip(RoundedCornerShape(22.dp))
                                .clickable(
                                    interactionSource = remember { MutableInteractionSource() },
                                    indication = null,
                                ) { onSelectTab(index) },
                            contentAlignment = Alignment.Center,
                        ) {
                            Column(
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.Center,
                            ) {
                                Icon(
                                    imageVector = tab.icon,
                                    contentDescription = tab.label,
                                    tint = if (isSelected) {
                                        MaterialTheme.colorScheme.primary
                                    } else {
                                        Color(0xFF1A1A1A)
                                    },
                                    modifier = Modifier.size(24.dp),
                                )
                                Spacer(modifier = Modifier.height(2.dp))
                                Text(
                                    text = tab.label,
                                    style = MaterialTheme.typography.labelSmall.copy(
                                        fontSize = 11.sp,
                                        fontWeight = if (isSelected) FontWeight.Medium else FontWeight.Normal,
                                    ),
                                    color = if (isSelected) {
                                        MaterialTheme.colorScheme.primary
                                    } else {
                                        Color(0xFF1A1A1A)
                                    },
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
