package com.mewbox.tv.ui.web

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.OpenInBrowser
import androidx.compose.material.icons.rounded.Public
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.mewbox.tv.bean.WebOptions
import com.mewbox.tv.ui.theme.MewBoxTheme
import timber.log.Timber

/**
 * 内部 Webview 容器界面（严格对齐 FongMi WebActivity）
 * 支持打开外部/本地网页、保持 Cookie 鉴权、网盘扫码以及各类拓展 Webview 交互。
 */
class WebActivity : ComponentActivity() {

    companion object {
        const val EXTRA_OPTIONS = "extra_options"

        @Volatile
        private var lastOpenTime = 0L
        @Volatile
        private var lastOpenUrl = ""

        fun open(context: Context, url: String, title: String = "") {
            open(context, WebOptions(url = url, title = title))
        }

        fun open(context: Context, options: WebOptions) {
            if (!options.isValid()) {
                Timber.w("拒绝启动 Webview: 无效的 URL (${options.url})")
                return
            }
            val now = System.currentTimeMillis()
            if (options.url == lastOpenUrl && now - lastOpenTime < 1200L) {
                Timber.d("短时间内忽略重复调起 Webview: ${options.url}")
                return
            }
            lastOpenTime = now
            lastOpenUrl = options.url
            val intent = Intent(context, WebActivity::class.java).apply {
                val optStr = if (options.hasCookies() || options.title.isNotEmpty() || options.mode.isNotEmpty()) {
                    options.toJsonString()
                } else {
                    options.url
                }
                putExtra(EXTRA_OPTIONS, optStr)
                if (context !is Activity) {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                }
            }
            context.startActivity(intent)
        }
    }

    private var fileCallback: ValueCallback<Array<Uri>>? = null
    private val fileChooserLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        val uris = WebChromeClient.FileChooserParams.parseResult(result.resultCode, result.data)
        fileCallback?.onReceiveValue(uris)
        fileCallback = null
    }

    private var webViewRef: WebView? = null
    private val mainHandler = Handler(Looper.getMainLooper())
    private var webOptions: WebOptions = WebOptions()

    private val loginCheckRunnable = object : Runnable {
        override fun run() {
            if (isFinishing || isDestroyed) return
            val web = webViewRef ?: return
            if (webOptions.hasCookies() && isCookieComplete(webOptions, web.url.orEmpty())) {
                CookieManager.getInstance().flush()
                Toast.makeText(this@WebActivity, "登录认证成功", Toast.LENGTH_SHORT).show()
                finish()
                return
            }
            mainHandler.postDelayed(this, 1000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // 遵循开发规范：强制软件不受系统深色模式控制
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

        val rawOpt = intent.getStringExtra(EXTRA_OPTIONS).orEmpty()
        webOptions = WebOptions.fromJsonString(rawOpt)
        if (!webOptions.isValid()) {
            Toast.makeText(this, "无效的网页链接", Toast.LENGTH_SHORT).show()
            finish()
            return
        }

        setContent {
            MewBoxTheme(darkTheme = false) {
                WebScreen(
                    options = webOptions,
                    onClose = { finish() },
                    onOpenFileChooser = { callback, params ->
                        fileCallback?.onReceiveValue(null)
                        fileCallback = callback
                        try {
                            fileChooserLauncher.launch(params.createIntent())
                        } catch (e: Exception) {
                            Timber.e(e, "调起文件选择器失败")
                            fileCallback?.onReceiveValue(null)
                            fileCallback = null
                        }
                    },
                    onWebViewCreated = { webView ->
                        webViewRef = webView
                        if (webOptions.hasCookies()) {
                            mainHandler.postDelayed(loginCheckRunnable, 1000)
                        }
                    },
                )
            }
        }
    }

    private fun isCookieComplete(options: WebOptions, currentUrl: String): Boolean {
        if (!options.hasCookies()) return false
        val manager = CookieManager.getInstance()
        return options.cookie.any { group ->
            val targetUrl = group.url.ifBlank { options.url }
            val cookiesStr = manager.getCookie(targetUrl).orEmpty()
            if (cookiesStr.isBlank()) return@any false
            val cookieMap = cookiesStr.split(";")
                .mapNotNull {
                    val pair = it.split("=", limit = 2)
                    if (pair.size == 2) pair[0].trim() to pair[1].trim() else null
                }.toMap()
            group.keys.all { key -> cookieMap[key]?.isNotEmpty() == true }
        }
    }

    override fun onDestroy() {
        mainHandler.removeCallbacks(loginCheckRunnable)
        fileCallback?.onReceiveValue(null)
        fileCallback = null
        webViewRef?.apply {
            stopLoading()
            clearHistory()
            destroy()
        }
        webViewRef = null
        super.onDestroy()
    }
}

@SuppressLint("SetJavaScriptEnabled")
@Composable
private fun WebScreen(
    options: WebOptions,
    onClose: () -> Unit,
    onOpenFileChooser: (ValueCallback<Array<Uri>>, WebChromeClient.FileChooserParams) -> Unit,
    onWebViewCreated: (WebView) -> Unit,
) {
    var pageTitle by remember { mutableStateOf(options.title.ifBlank { "网页浏览" }) }
    var currentUrl by remember { mutableStateOf(options.url) }
    var progress by remember { mutableFloatStateOf(0f) }
    var isLoading by remember { mutableStateOf(true) }
    var canGoBack by remember { mutableStateOf(false) }
    var webViewInstance by remember { mutableStateOf<WebView?>(null) }

    BackHandler {
        val web = webViewInstance
        if (web != null && web.canGoBack()) {
            web.goBack()
        } else {
            onClose()
        }
    }

    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background,
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            // 顶部导航栏
            Surface(
                modifier = Modifier.fillMaxWidth(),
                color = MaterialTheme.colorScheme.surface,
                shadowElevation = 2.dp,
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .windowInsetsPadding(WindowInsets.statusBars),
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(56.dp)
                            .padding(horizontal = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        // 返回键
                        IconButton(
                            onClick = {
                                val web = webViewInstance
                                if (web != null && web.canGoBack()) {
                                    web.goBack()
                                } else {
                                    onClose()
                                }
                            },
                        ) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Rounded.ArrowBack,
                                contentDescription = "返回",
                                tint = MaterialTheme.colorScheme.onSurface,
                            )
                        }

                        // 页面标题与域名
                        Column(
                            modifier = Modifier
                                .weight(1f)
                                .padding(horizontal = 4.dp),
                        ) {
                            Text(
                                text = pageTitle,
                                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
                                color = MaterialTheme.colorScheme.onSurface,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            val uri = remember(currentUrl) { runCatching { Uri.parse(currentUrl) }.getOrNull() }
                            val host = uri?.host.orEmpty()
                            if (host.isNotEmpty()) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Icon(
                                        imageVector = if (uri?.scheme == "https") Icons.Rounded.Lock else Icons.Rounded.Public,
                                        contentDescription = null,
                                        modifier = Modifier.size(11.dp),
                                        tint = MaterialTheme.colorScheme.outline,
                                    )
                                    Spacer(modifier = Modifier.width(3.dp))
                                    Text(
                                        text = host,
                                        fontSize = 11.sp,
                                        color = MaterialTheme.colorScheme.outline,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                }
                            }
                        }

                        // 刷新按钮
                        IconButton(onClick = { webViewInstance?.reload() }) {
                            Icon(
                                imageVector = Icons.Rounded.Refresh,
                                contentDescription = "刷新",
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }

                        // 调用系统外部浏览器打开
                        IconButton(
                            onClick = {
                                val uri = runCatching { Uri.parse(currentUrl) }.getOrNull()
                                if (uri != null) {
                                    val intent = Intent(Intent.ACTION_VIEW, uri).apply {
                                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                    }
                                    runCatching { webViewInstance?.context?.startActivity(intent) }
                                }
                            },
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.OpenInBrowser,
                                contentDescription = "在浏览器中打开",
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }

                        // 关闭按钮
                        IconButton(onClick = onClose) {
                            Icon(
                                imageVector = Icons.Rounded.Close,
                                contentDescription = "关闭",
                                tint = MaterialTheme.colorScheme.onSurface,
                            )
                        }
                    }

                    // 进度条
                    AnimatedVisibility(
                        visible = isLoading,
                        enter = fadeIn(),
                        exit = fadeOut(),
                    ) {
                        LinearProgressIndicator(
                            progress = { progress },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(2.5.dp),
                            color = MaterialTheme.colorScheme.primary,
                            trackColor = Color.Transparent,
                        )
                    }
                }
            }

            // Webview 核心区域
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .background(Color.White),
            ) {
                AndroidView(
                    modifier = Modifier.fillMaxSize(),
                    factory = { ctx ->
                        WebView(ctx).apply {
                            layoutParams = ViewGroup.LayoutParams(
                                ViewGroup.LayoutParams.MATCH_PARENT,
                                ViewGroup.LayoutParams.MATCH_PARENT,
                            )
                            settings.apply {
                                javaScriptEnabled = true
                                domStorageEnabled = true
                                databaseEnabled = true
                                useWideViewPort = true
                                loadWithOverviewMode = true
                                setSupportZoom(true)
                                builtInZoomControls = true
                                displayZoomControls = false
                                mediaPlaybackRequiresUserGesture = false
                                allowFileAccess = false
                                allowContentAccess = false
                                mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
                            }

                            CookieManager.getInstance().let { manager ->
                                manager.setAcceptCookie(true)
                                manager.setAcceptThirdPartyCookies(this@apply, true)
                            }

                            webChromeClient = object : WebChromeClient() {
                                override fun onProgressChanged(view: WebView?, newProgress: Int) {
                                    progress = newProgress / 100f
                                    isLoading = newProgress < 100
                                }

                                override fun onReceivedTitle(view: WebView?, title: String?) {
                                    if (!title.isNullOrBlank() && !title.startsWith("http")) {
                                        pageTitle = title
                                    }
                                }

                                override fun onShowFileChooser(
                                    webView: WebView?,
                                    filePathCallback: ValueCallback<Array<Uri>>?,
                                    fileChooserParams: FileChooserParams?,
                                ): Boolean {
                                    if (filePathCallback != null && fileChooserParams != null) {
                                        onOpenFileChooser(filePathCallback, fileChooserParams)
                                        return true
                                    }
                                    return false
                                }
                            }

                            webViewClient = object : WebViewClient() {
                                override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                                    val uri = request?.url ?: return false
                                    val uriString = uri.toString()

                                    // 对齐 FongMi：tv-spider://close 关闭内部页面
                                    if (uriString == "tv-spider://close") {
                                        onClose()
                                        return true
                                    }

                                    val scheme = uri.scheme.orEmpty().lowercase()
                                    if (scheme != "http" && scheme != "https") {
                                        // 外部 scheme (weixin, alipays, etc.)
                                        return try {
                                            val intent = Intent(Intent.ACTION_VIEW, uri).apply {
                                                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                            }
                                            ctx.startActivity(intent)
                                            true
                                        } catch (_: Exception) {
                                            true
                                        }
                                    }
                                    return false
                                }

                                override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                                    if (!url.isNullOrBlank()) currentUrl = url
                                    isLoading = true
                                    canGoBack = view?.canGoBack() == true
                                }

                                override fun onPageFinished(view: WebView?, url: String?) {
                                    if (!url.isNullOrBlank()) currentUrl = url
                                    isLoading = false
                                    canGoBack = view?.canGoBack() == true
                                    CookieManager.getInstance().flush()
                                }
                            }

                            loadUrl(options.url)
                            webViewInstance = this
                            onWebViewCreated(this)
                        }
                    },
                    update = { view ->
                        webViewInstance = view
                    },
                )
            }
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            webViewInstance?.apply {
                stopLoading()
            }
        }
    }
}
