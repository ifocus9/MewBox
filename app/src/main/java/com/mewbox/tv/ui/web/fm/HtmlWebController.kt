package com.mewbox.tv.ui.web.fm

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import android.net.http.SslError
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.webkit.ConsoleMessage
import android.webkit.CookieManager
import android.webkit.RenderProcessGoneDetail
import android.webkit.SslErrorHandler
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import com.mewbox.tv.api.config.SettingsRepository
import com.mewbox.tv.api.net.CookieBridge
import com.mewbox.tv.api.net.DriveCheckService
import com.mewbox.tv.api.net.LocalMessageBridge
import com.mewbox.tv.api.net.WebCall
import com.mewbox.tv.api.parser.ResultParser
import com.mewbox.tv.api.parser.quoteJsString
import com.mewbox.tv.db.ConfigDao
import com.mewbox.tv.db.SiteDao
import com.mewbox.tv.domain.WebHomeInlineStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import timber.log.Timber
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume

data class HtmlWebState(
    val loadedRootUrl: String = "",
    val currentUrl: String = "",
    val pageTitle: String = "",
    val progress: Int = 0,
    val isLoading: Boolean = false,
    val isInitialPageLoading: Boolean = true,
    val canGoBack: Boolean = false,
    val hasJsBackHandler: Boolean = false,
    val chromeOptions: WebHomeChromeOptions = WebHomeChromeOptions(),
    val isFullscreenVideo: Boolean = false,
)

sealed interface HtmlWebNavEvent {
    data class NavigateToPlay(val siteKey: String, val vodId: String, val vodName: String, val vodPic: String) : HtmlWebNavEvent
    data class NavigateToDetail(val siteKey: String, val vodId: String, val vodName: String, val vodPic: String) : HtmlWebNavEvent
    data class NavigateToSearch(val keyword: String) : HtmlWebNavEvent
    data class OpenFileChooser(val intent: Intent, val callback: ValueCallback<Array<Uri>>) : HtmlWebNavEvent
}

/**
 * HTML 订阅源 WebView 容器控制器（对齐 MewPlayer HomeWebController）。
 *
 * 在跨页面导航（如进入全屏播放器或详情页）期间保持 WebView 实例存活，
 * 以便返回时秒回原浏览位置，并支持后台执行 `window.__fmWebHomeInlineResolver` 动态选集解析。
 */
@Singleton
class HtmlWebController @Inject constructor(
    @param:ApplicationContext private val appContext: Context,
    private val webCall: WebCall,
    private val driveCheckService: DriveCheckService,
    private val localMessageBridge: LocalMessageBridge,
    private val webHomeInlineStore: WebHomeInlineStore,
    private val configDao: ConfigDao,
    private val siteDao: SiteDao,
    private val settingsRepository: SettingsRepository,
) {
    private val mainHandler = Handler(Looper.getMainLooper())

    private val _state = MutableStateFlow(HtmlWebState())
    val state: StateFlow<HtmlWebState> = _state.asStateFlow()

    private val _navEvents = MutableSharedFlow<HtmlWebNavEvent>(extraBufferCapacity = 8)
    val navEvents: SharedFlow<HtmlWebNavEvent> = _navEvents.asSharedFlow()

    private var webView: WebView? = null
    private var webViewContainer: FrameLayout? = null
    private var fullscreenContainer: FrameLayout? = null
    private var customView: View? = null
    private var customViewCallback: WebChromeClient.CustomViewCallback? = null
    private var currentViewport: WebHomeViewport = WebHomeViewport()

    /** 切换 HTML 订阅源：loadSubscriptionUrl 置位 pending，onPageStarted 置位 armed，新文档提交后 clearHistory */
    private var clearHistoryPending = false
    private var clearHistoryArmed = false
    /** 当前已在 WebView 中实际提交文档的订阅根地址 */
    private var committedRootUrl = ""

    private val pendingInlineCallbacks = ConcurrentHashMap<String, (JsonObject?) -> Unit>()

    companion object {
        private const val PAUSE_MEDIA_SCRIPT =
            "(function(){try{document.querySelectorAll('video,audio').forEach(function(m){try{m.pause();}catch(e){}});}catch(e){}})();"
    }

    init {
        webHomeInlineStore.jsResolver = WebHomeInlineStore.InlineJsResolver { vodId, flag, episodeId, episodeName ->
            resolveInlineEpisodeViaJs(vodId, flag, episodeId, episodeName)
        }
    }

    private val bridgeCallbacks = object : HomeWebBridge.BridgeCallbacks {
        override fun getCurrentPageUrl(): String = webView?.url.orEmpty().ifBlank { _state.value.currentUrl }

        override fun getViewport(): WebHomeViewport = currentViewport

        override fun evaluateJs(script: String) {
            webView?.evaluateJavascript(script, null)
        }

        override fun onNavigateToPlay(siteKey: String, vodId: String, vodName: String, vodPic: String) {
            _navEvents.tryEmit(HtmlWebNavEvent.NavigateToPlay(siteKey, vodId, vodName, vodPic))
        }

        override fun onNavigateToDetail(siteKey: String, vodId: String, vodName: String, vodPic: String) {
            _navEvents.tryEmit(HtmlWebNavEvent.NavigateToDetail(siteKey, vodId, vodName, vodPic))
        }

        override fun onNavigateToSearch(keyword: String) {
            _navEvents.tryEmit(HtmlWebNavEvent.NavigateToSearch(keyword))
        }

        override fun onUpdateChrome(options: WebHomeChromeOptions) {
            _state.update { it.copy(chromeOptions = options) }
        }

        override fun getChromeOptions(): WebHomeChromeOptions = _state.value.chromeOptions

        override fun onSetJsBackHandler(enabled: Boolean) {
            _state.update { it.copy(hasJsBackHandler = enabled) }
        }

        override fun onNavigateBack() {
            handleBackPress(onFallback = {})
        }

        override fun onReloadPage() {
            reload()
        }
    }

    fun getOrCreateWebView(context: Context = appContext): WebView {
        val existing = webView
        if (existing != null) {
            return existing
        }
        return createWebView(context.applicationContext).also { created ->
            webView = created
            webViewContainer?.let { container ->
                attachWebViewToContainer(created, container)
            }
        }
    }

    fun bindWebViewContainer(container: FrameLayout?) {
        webViewContainer = container
        if (container == null) return
        val wv = getOrCreateWebView(container.context)
        attachWebViewToContainer(wv, container)
    }

    private fun attachWebViewToContainer(wv: WebView, container: FrameLayout) {
        if (wv.parent !== container) {
            (wv.parent as? ViewGroup)?.removeView(wv)
            container.removeAllViews()
            container.addView(
                wv,
                FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT,
                ),
            )
        }
    }

    fun bindFullscreenContainer(container: FrameLayout?) {
        fullscreenContainer = container
        val view = customView
        if (container != null && view != null && view.parent != container) {
            (view.parent as? ViewGroup)?.removeView(view)
            container.addView(
                view,
                FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT,
                ),
            )
            container.visibility = View.VISIBLE
        }
    }

    fun loadSubscriptionUrl(url: String, forceReload: Boolean = false) {
        val target = url.trim()
        if (!isLoadableUrl(target)) return
        val wv = getOrCreateWebView(appContext)
        val switched = _state.value.loadedRootUrl != target
        if (!forceReload && !switched && committedRootUrl == target && !wv.url.isNullOrBlank()) {
            _state.update { it.copy(isInitialPageLoading = false) }
            return
        }
        // 切换到另一个 HTML 订阅源（或强制重载根地址）：新网页文档提交后清空历史，
        // 避免系统返回键退回到上一个 HTML 订阅源导致页面与当前激活订阅不一致
        if (switched || forceReload || committedRootUrl != target) {
            clearHistoryPending = true
            clearHistoryArmed = false
            committedRootUrl = ""
        }
        if (switched) {
            hideCustomView()
            wv.stopLoading()
            wv.evaluateJavascript(PAUSE_MEDIA_SCRIPT, null)
        }
        _state.update {
            it.copy(
                loadedRootUrl = target,
                currentUrl = target,
                pageTitle = "",
                isLoading = true,
                isInitialPageLoading = true,
                progress = 10,
                canGoBack = false,
                hasJsBackHandler = false,
                chromeOptions = WebHomeChromeOptions(),
            )
        }
        wv.loadUrl(target)
    }

    private fun isLoadableUrl(url: String): Boolean {
        return CookieBridge.isHttpUrl(url) || url.startsWith("file://", ignoreCase = true)
    }

    fun reload() {
        val wv = webView ?: return
        val rootUrl = _state.value.loadedRootUrl
        val currentUrl = wv.url.orEmpty()
        if (rootUrl.isBlank() && currentUrl.isBlank()) return
        _state.update { it.copy(isLoading = true, isInitialPageLoading = true, progress = 10) }
        // 若当前激活的 HTML 订阅尚未完成首次文档提交，直接加载目标订阅根地址，避免误刷新切换前残留的旧网页
        if (
            currentUrl.isBlank() ||
            currentUrl.equals("about:blank", ignoreCase = true) ||
            clearHistoryPending ||
            clearHistoryArmed ||
            (rootUrl.isNotBlank() && committedRootUrl != rootUrl)
        ) {
            wv.loadUrl(rootUrl.ifBlank { currentUrl })
        } else {
            wv.reload()
        }
    }

    fun updateViewport(viewport: WebHomeViewport) {
        if (currentViewport == viewport) return
        currentViewport = viewport
        webView?.evaluateJavascript(viewport.buildInjectionScript(), null)
    }

    fun onScreenResume() {
        val wv = webView ?: return
        wv.onResume()
        wv.resumeTimers()
        wv.evaluateJavascript(
            "try { window.dispatchEvent(new CustomEvent('fmresume')); } catch(e) {}",
            null,
        )
    }

    fun onScreenPause() {
        val wv = webView ?: return
        wv.evaluateJavascript(
            "try { window.dispatchEvent(new CustomEvent('fmpause')); } catch(e) {}",
            null,
        )
        CookieBridge.flush()
    }

    fun handleBackPress(onFallback: () -> Unit) {
        if (_state.value.isFullscreenVideo && customView != null) {
            hideCustomView()
            return
        }
        val wv = webView
        if (wv == null || clearHistoryPending || clearHistoryArmed || committedRootUrl != _state.value.loadedRootUrl) {
            onFallback()
            return
        }
        if (_state.value.hasJsBackHandler) {
            val handledOnce = AtomicBoolean(false)
            val fallbackAction = {
                if (canNavigateBack(wv)) {
                    wv.goBack()
                    syncBackState(wv)
                } else {
                    onFallback()
                }
            }
            mainHandler.postDelayed({
                if (handledOnce.compareAndSet(false, true)) {
                    fallbackAction()
                }
            }, 350L)

            val js = """
                (function() {
                    try {
                        if (typeof window.__fmBackHandler === 'function') {
                            return Boolean(window.__fmBackHandler());
                        }
                    } catch (e) {}
                    return false;
                })();
            """.trimIndent()
            wv.evaluateJavascript(js) { result ->
                if (handledOnce.compareAndSet(false, true)) {
                    val consumed = result?.trim()?.equals("true", ignoreCase = true) == true
                    if (!consumed) {
                        fallbackAction()
                    }
                }
            }
            return
        }

        if (canNavigateBack(wv)) {
            wv.goBack()
            syncBackState(wv)
        } else {
            onFallback()
        }
    }

    fun destroyWebView() {
        hideCustomView()
        clearHistoryPending = false
        clearHistoryArmed = false
        committedRootUrl = ""
        val wv = webView ?: return
        webView = null
        runCatching {
            (wv.parent as? ViewGroup)?.removeView(wv)
            wv.stopLoading()
            wv.removeJavascriptInterface(FmJsSdk.BRIDGE_NAME)
            wv.removeJavascriptInterface("fongmiInlineResolver")
            wv.webChromeClient = null
            wv.destroy()
        }
        _state.value = HtmlWebState()
    }

    @SuppressLint("SetJavaScriptEnabled")
    @Suppress("DEPRECATION")
    private fun createWebView(context: Context): WebView {
        val wv = WebView(context)
        wv.setBackgroundColor(Color.WHITE)
        wv.setLayerType(View.LAYER_TYPE_HARDWARE, null)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            wv.setRendererPriorityPolicy(WebView.RENDERER_PRIORITY_IMPORTANT, true)
        }
        wv.overScrollMode = View.OVER_SCROLL_NEVER
        wv.scrollBarStyle = View.SCROLLBARS_INSIDE_OVERLAY
        wv.isFocusable = true
        wv.isFocusableInTouchMode = true

        val settings = wv.settings
        settings.javaScriptEnabled = true
        settings.domStorageEnabled = true
        settings.databaseEnabled = true
        settings.allowFileAccess = true
        settings.allowContentAccess = true
        settings.allowFileAccessFromFileURLs = true
        settings.allowUniversalAccessFromFileURLs = true
        settings.mediaPlaybackRequiresUserGesture = false
        settings.mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
        settings.setRenderPriority(WebSettings.RenderPriority.HIGH)
        settings.useWideViewPort = true
        settings.loadWithOverviewMode = true
        settings.setSupportZoom(false)
        settings.builtInZoomControls = false
        settings.displayZoomControls = false
        settings.textZoom = 100
        settings.cacheMode = WebSettings.LOAD_DEFAULT
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            settings.offscreenPreRaster = true
        }
        settings.javaScriptCanOpenWindowsAutomatically = true
        settings.setSupportMultipleWindows(false)

        runCatching {
            val cm = CookieManager.getInstance()
            cm.setAcceptCookie(true)
            cm.setAcceptThirdPartyCookies(wv, true)
        }

        val bridge = HomeWebBridge(
            context = appContext,
            webCall = webCall,
            driveCheckService = driveCheckService,
            localMessageBridge = localMessageBridge,
            webHomeInlineStore = webHomeInlineStore,
            configDao = configDao,
            siteDao = siteDao,
            settingsRepository = settingsRepository,
            callbacks = bridgeCallbacks,
        )
        wv.addJavascriptInterface(bridge, FmJsSdk.BRIDGE_NAME)
        wv.addJavascriptInterface(InlineResolverCallbackBridge(), "fongmiInlineResolver")

        wv.webViewClient = object : WebViewClient() {
            override fun onPageStarted(view: WebView, url: String?, favicon: Bitmap?) {
                super.onPageStarted(view, url, favicon)
                if (clearHistoryPending) {
                    clearHistoryPending = false
                    clearHistoryArmed = true
                }
                _state.update {
                    it.copy(
                        currentUrl = url.orEmpty(),
                        isLoading = true,
                        canGoBack = canNavigateBack(view),
                        hasJsBackHandler = false,
                    )
                }
                injectSdk(view)
            }

            override fun onPageCommitVisible(view: WebView, url: String?) {
                super.onPageCommitVisible(view, url)
                if (clearHistoryPending || clearHistoryArmed) {
                    clearHistoryPending = false
                    clearHistoryArmed = false
                    committedRootUrl = _state.value.loadedRootUrl
                    view.clearHistory()
                } else if (committedRootUrl.isEmpty() && _state.value.loadedRootUrl.isNotBlank()) {
                    committedRootUrl = _state.value.loadedRootUrl
                }
                _state.update {
                    it.copy(canGoBack = canNavigateBack(view))
                }
                injectSdk(view)
            }

            override fun onPageFinished(view: WebView, url: String?) {
                super.onPageFinished(view, url)
                if (clearHistoryPending || clearHistoryArmed) {
                    clearHistoryPending = false
                    clearHistoryArmed = false
                    committedRootUrl = _state.value.loadedRootUrl
                    view.clearHistory()
                } else if (committedRootUrl.isEmpty() && _state.value.loadedRootUrl.isNotBlank()) {
                    committedRootUrl = _state.value.loadedRootUrl
                }
                injectSdk(view)
                val title = view.title?.trim().orEmpty()
                _state.update {
                    it.copy(
                        currentUrl = url.orEmpty().ifBlank { it.currentUrl },
                        pageTitle = if (title.isNotBlank() && !title.startsWith("http")) title else it.pageTitle,
                        isLoading = false,
                        isInitialPageLoading = false,
                        progress = 100,
                        canGoBack = canNavigateBack(view),
                    )
                }
                CookieBridge.flush()
            }

            override fun doUpdateVisitedHistory(view: WebView, url: String?, isReload: Boolean) {
                super.doUpdateVisitedHistory(view, url, isReload)
                if (clearHistoryPending || clearHistoryArmed) {
                    // 新订阅源文档已提交：丢弃切换前上一个 HTML 源的历史记录（对齐 MewPlayer HomeWebController）
                    committedRootUrl = _state.value.loadedRootUrl
                    view.clearHistory()
                }
                _state.update {
                    it.copy(
                        currentUrl = url.orEmpty().ifBlank { it.currentUrl },
                        canGoBack = canNavigateBack(view),
                    )
                }
                injectSdk(view)
            }

            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                val uri = request.url ?: return false
                val scheme = uri.scheme?.lowercase().orEmpty()
                if (scheme == "http" || scheme == "https" || scheme == "file" || scheme == "about" || scheme == "data") {
                    return false
                }
                runCatching {
                    val intent = if (scheme == "intent") {
                        Intent.parseUri(uri.toString(), Intent.URI_INTENT_SCHEME)
                    } else {
                        Intent(Intent.ACTION_VIEW, uri)
                    }
                    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    appContext.startActivity(intent)
                }
                return true
            }

            @SuppressLint("WebViewClientOnReceivedSslError")
            override fun onReceivedSslError(view: WebView?, handler: SslErrorHandler?, error: SslError?) {
                handler?.proceed()
            }

            override fun onReceivedError(view: WebView?, request: WebResourceRequest?, error: WebResourceError?) {
                super.onReceivedError(view, request, error)
                if (request?.isForMainFrame == true) {
                    _state.update {
                        it.copy(
                            isLoading = false,
                            isInitialPageLoading = false,
                        )
                    }
                }
            }

            override fun onRenderProcessGone(view: WebView?, detail: RenderProcessGoneDetail?): Boolean {
                Timber.e("HTML 源 WebView 渲染进程退出，正在自动恢复")
                val targetUrl = _state.value.loadedRootUrl
                mainHandler.post {
                    destroyWebView()
                    if (targetUrl.isNotBlank()) {
                        loadSubscriptionUrl(targetUrl, forceReload = true)
                    }
                }
                return true
            }
        }

        wv.webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(view: WebView, newProgress: Int) {
                super.onProgressChanged(view, newProgress)
                if (newProgress >= 20) {
                    injectSdk(view)
                }
                _state.update {
                    it.copy(
                        progress = newProgress,
                        isLoading = newProgress < 100,
                        isInitialPageLoading = if (newProgress >= 85) false else it.isInitialPageLoading,
                        canGoBack = canNavigateBack(view),
                    )
                }
            }

            override fun onReceivedTitle(view: WebView?, title: String?) {
                super.onReceivedTitle(view, title)
                if (clearHistoryPending) return
                val clean = title?.trim().orEmpty()
                if (clean.isNotBlank() && !clean.startsWith("http://") && !clean.startsWith("https://") && !clean.startsWith("file://")) {
                    _state.update { it.copy(pageTitle = clean) }
                }
            }

            override fun onShowCustomView(view: View?, callback: CustomViewCallback?) {
                if (view == null) return
                if (customView != null) {
                    callback?.onCustomViewHidden()
                    return
                }
                customView = view
                customViewCallback = callback
                _state.update { it.copy(isFullscreenVideo = true) }
                fullscreenContainer?.let { container ->
                    container.removeAllViews()
                    container.addView(
                        view,
                        FrameLayout.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.MATCH_PARENT,
                        ),
                    )
                    container.visibility = View.VISIBLE
                }
            }

            override fun onHideCustomView() {
                hideCustomView()
            }

            override fun onShowFileChooser(
                webView: WebView?,
                filePathCallback: ValueCallback<Array<Uri>>?,
                fileChooserParams: FileChooserParams?,
            ): Boolean {
                if (filePathCallback == null) return false
                val intent = runCatching { fileChooserParams?.createIntent() }.getOrNull()
                    ?: Intent(Intent.ACTION_GET_CONTENT).apply {
                        addCategory(Intent.CATEGORY_OPENABLE)
                        type = "*/*"
                    }
                val emitted = _navEvents.tryEmit(HtmlWebNavEvent.OpenFileChooser(intent, filePathCallback))
                if (!emitted) {
                    filePathCallback.onReceiveValue(null)
                }
                return emitted
            }

            override fun onConsoleMessage(consoleMessage: ConsoleMessage?): Boolean {
                if (consoleMessage != null && consoleMessage.messageLevel() == ConsoleMessage.MessageLevel.ERROR) {
                    Timber.w(
                        "WebView Console Error: %s (%s:%d)",
                        consoleMessage.message(),
                        consoleMessage.sourceId(),
                        consoleMessage.lineNumber(),
                    )
                }
                return true
            }
        }

        return wv
    }

    private fun hideCustomView() {
        val view = customView
        val callback = customViewCallback
        customView = null
        customViewCallback = null
        _state.update { it.copy(isFullscreenVideo = false) }
        fullscreenContainer?.let { container ->
            if (view != null) container.removeView(view)
            container.visibility = View.GONE
        }
        runCatching { callback?.onCustomViewHidden() }
    }

    private fun injectSdk(view: WebView) {
        view.evaluateJavascript(FmJsSdk.buildScript(currentViewport), null)
    }

    private fun canNavigateBack(view: WebView): Boolean {
        if (clearHistoryPending || clearHistoryArmed || committedRootUrl != _state.value.loadedRootUrl) {
            return false
        }
        return view.canGoBack()
    }

    private fun syncBackState(view: WebView) {
        _state.update { it.copy(canGoBack = canNavigateBack(view)) }
    }

    private inner class InlineResolverCallbackBridge {
        @android.webkit.JavascriptInterface
        fun onResolved(reqId: String?, jsonStr: String?) {
            val id = reqId ?: return
            val cb = pendingInlineCallbacks.remove(id) ?: return
            val obj = if (jsonStr.isNullOrBlank()) null else ResultParser.parseJson(jsonStr) as? JsonObject
            cb(obj)
        }
    }

    private suspend fun resolveInlineEpisodeViaJs(
        vodId: String,
        flag: String,
        episodeId: String,
        episodeName: String,
    ): JsonObject? = withTimeoutOrNull(12_000L) {
        suspendCancellableCoroutine { cont ->
            val reqId = UUID.randomUUID().toString()
            pendingInlineCallbacks[reqId] = { result ->
                if (cont.isActive) cont.resume(result)
            }
            cont.invokeOnCancellation {
                pendingInlineCallbacks.remove(reqId)
            }
            val payload = buildJsonObject {
                put("vodId", vodId)
                put("flag", flag)
                put("id", episodeId)
                put("url", episodeId)
                put("name", episodeName)
            }.toString()
            val safeReqId = quoteJsString(reqId)
            val script = """
                (function() {
                    var fn = window.__fmWebHomeInlineResolver || window.__fmYmvidResolveEpisode;
                    if (typeof fn !== 'function') {
                        window.fongmiInlineResolver.onResolved($safeReqId, '');
                        return;
                    }
                    Promise.resolve(fn($payload)).then(function(res) {
                        var out = typeof res === 'string' ? { url: res } : (res || {});
                        window.fongmiInlineResolver.onResolved($safeReqId, JSON.stringify(out));
                    }).catch(function() {
                        window.fongmiInlineResolver.onResolved($safeReqId, '');
                    });
                })();
            """.trimIndent()
            mainHandler.post {
                val wv = webView
                if (wv == null) {
                    pendingInlineCallbacks.remove(reqId)?.invoke(null)
                } else {
                    wv.onResume()
                    wv.resumeTimers()
                    wv.evaluateJavascript(script, null)
                }
            }
        }
    }
}
