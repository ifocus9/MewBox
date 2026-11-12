package com.mewbox.tv.ui.web.fm

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.webkit.JavascriptInterface
import com.mewbox.tv.BuildConfig
import com.mewbox.tv.api.config.SettingsRepository
import com.mewbox.tv.api.net.CookieBridge
import com.mewbox.tv.api.net.DriveCheckService
import com.mewbox.tv.api.net.LocalMessageBridge
import com.mewbox.tv.api.net.WebCall
import com.mewbox.tv.api.net.WebResourceGateway
import com.mewbox.tv.api.parser.ResultParser
import com.mewbox.tv.util.AppToast
import com.mewbox.tv.api.parser.optBoolean
import com.mewbox.tv.api.parser.optLong
import com.mewbox.tv.api.parser.optObj
import com.mewbox.tv.api.parser.optString
import com.mewbox.tv.api.parser.quoteJsString
import com.mewbox.tv.api.parser.toJsonElement
import com.mewbox.tv.api.parser.toJsonObjectOrEmpty
import com.mewbox.tv.bean.WebOptions
import com.mewbox.tv.db.ConfigDao
import com.mewbox.tv.db.SiteDao
import com.mewbox.tv.domain.SiteRepository
import com.mewbox.tv.domain.WebHomeInlineStore
import com.mewbox.tv.ui.web.WebActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import timber.log.Timber
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.resume

/**
 * 网页端控制宿主顶栏/底栏/侧边悬浮条的 Chrome 配置项（对齐 MewPlayer WebHomeChromeOptions）。
 */
data class WebHomeChromeOptions(
    val bottomTabInset: Boolean = false,
    val floatingControls: Boolean = true,
    val edgeHandle: Boolean = true,
) {
    fun toJson(): JsonObject = buildJsonObject {
        put("bottomTabInset", bottomTabInset)
        put("floatingControls", floatingControls)
        put("edgeHandle", edgeHandle)
    }
}

/**
 * 注入到 WebView 的原生桥接实现 `window.fongmiBridge`（对齐 MewPlayer HomeWebBridge）。
 */
class HomeWebBridge(
    private val context: Context,
    private val webCall: WebCall,
    private val driveCheckService: DriveCheckService,
    private val localMessageBridge: LocalMessageBridge,
    private val webHomeInlineStore: WebHomeInlineStore,
    private val configDao: ConfigDao,
    private val siteDao: SiteDao,
    private val settingsRepository: SettingsRepository,
    private val callbacks: BridgeCallbacks,
) {
    interface BridgeCallbacks {
        fun getCurrentPageUrl(): String
        fun getViewport(): WebHomeViewport
        fun evaluateJs(script: String)
        fun onNavigateToPlay(siteKey: String, vodId: String, vodName: String, vodPic: String)
        fun onNavigateToDetail(siteKey: String, vodId: String, vodName: String, vodPic: String)
        fun onNavigateToSearch(keyword: String)
        fun onUpdateChrome(options: WebHomeChromeOptions)
        fun getChromeOptions(): WebHomeChromeOptions
        fun onSetJsBackHandler(enabled: Boolean)
        fun onNavigateBack()
        fun onReloadPage()
    }

    companion object {
        private const val CACHE_PREFS_NAME = "fongmi_web_cache"
        private const val MAX_RESULTS_BUFFER = 64

        private val PAN_DEFAULT_URLS = mapOf(
            "quark" to "https://pan.quark.cn",
            "uc" to "https://drive.uc.cn",
            "ali" to "https://www.alipan.com",
            "aliyun" to "https://www.alipan.com",
            "alipan" to "https://www.alipan.com",
            "baidu" to "https://pan.baidu.com",
            "115" to "https://115.com",
            "tianyi" to "https://cloud.189.cn",
            "189" to "https://cloud.189.cn",
            "123" to "https://www.123pan.com",
            "123pan" to "https://www.123pan.com",
            "yidong" to "https://yun.139.com",
            "139" to "https://yun.139.com",
            "bili" to "https://www.bilibili.com",
            "bilibili" to "https://www.bilibili.com",
        )

        fun stripPush(rawUrl: String): String {
            val trimmed = rawUrl.trim()
            return if (trimmed.startsWith("push://", ignoreCase = true)) {
                trimmed.substring(7).trim()
            } else {
                trimmed
            }
        }
    }

    private val mainHandler = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val resultStore = ConcurrentHashMap<String, String>()
    private val resultOrder = ConcurrentHashMap<String, Long>()

    @JavascriptInterface
    fun resultLength(id: String?): Int {
        if (id.isNullOrEmpty()) return 0
        return resultStore[id]?.length ?: 0
    }

    @JavascriptInterface
    fun resultChunk(id: String?, offset: Int, length: Int): String {
        if (id.isNullOrEmpty() || offset < 0 || length <= 0) return ""
        val data = resultStore[id] ?: return ""
        if (offset >= data.length) return ""
        val end = (offset + length).coerceAtMost(data.length)
        return data.substring(offset, end)
    }

    @JavascriptInterface
    fun clearResult(id: String?) {
        if (id.isNullOrEmpty()) return
        resultStore.remove(id)
        resultOrder.remove(id)
    }

    @JavascriptInterface
    fun resourceUrl(url: String?, optionsJson: String?): String {
        return try {
            val targetUrl = url?.trim().orEmpty()
            if (!CookieBridge.isHttpUrl(targetUrl)) return ""
            val params = optionsJson.toJsonObjectOrEmpty()
            val port = localMessageBridge.listeningPort.takeIf { it > 0 } ?: localMessageBridge.start()
            WebResourceGateway.buildProxyUrl(port, targetUrl, params)
        } catch (e: Exception) {
            Timber.w(e, "同步生成 resourceUrl 代理地址失败")
            ""
        }
    }

    @JavascriptInterface
    fun buildResourceUrlSync(paramsJson: String?): String {
        return try {
            val params = paramsJson.toJsonObjectOrEmpty()
            val url = params.optString("url", "").trim()
            if (!CookieBridge.isHttpUrl(url)) return ""
            val port = localMessageBridge.listeningPort.takeIf { it > 0 } ?: localMessageBridge.start()
            WebResourceGateway.buildProxyUrl(port, url, params)
        } catch (e: Exception) {
            Timber.w(e, "同步生成 webResource 代理地址失败")
            ""
        }
    }

    @JavascriptInterface
    fun invoke(action: String?, paramsJson: String?, callbackId: String?) {
        val act = action?.trim().orEmpty()
        val cbId = callbackId?.trim().orEmpty()
        if (cbId.isEmpty()) return

        scope.launch {
            val pageUrl = awaitPageUrl()
            if (!isOriginAllowed(pageUrl)) {
                deliverError(cbId, "Untrusted page origin", 403)
                return@launch
            }
            try {
                val params = paramsJson.toJsonObjectOrEmpty()
                val data = dispatchAction(act, params, pageUrl)
                deliverSuccess(cbId, data)
            } catch (e: Exception) {
                Timber.w(e, "fongmiBridge 调用失败: action=%s", act)
                deliverError(cbId, e.message ?: "Bridge invocation failed", -1)
            }
        }
    }

    private suspend fun dispatchAction(action: String, params: JsonObject, pageUrl: String): JsonElement {
        return when (action) {
            "net.request" -> webCall.execute(params)
            "net.cookieGet" -> {
                val url = params.optString("url", pageUrl).ifBlank { pageUrl }
                val cookie = CookieBridge.get(url)
                buildJsonObject {
                    put("url", url)
                    put("cookie", cookie)
                }
            }
            "net.cookieSet" -> {
                val url = params.optString("url", pageUrl).ifBlank { pageUrl }
                val cookie = params.optString("cookie", "")
                val ok = CookieBridge.set(url, cookie)
                buildJsonObject { put("saved", ok) }
            }
            "net.cookieRemove" -> {
                val url = params.optString("url", pageUrl).ifBlank { pageUrl }
                val name = params.optString("name", "")
                val ok = CookieBridge.remove(url, name)
                buildJsonObject { put("removed", ok) }
            }
            "net.cookieClear" -> {
                val ok = CookieBridge.clearAll()
                buildJsonObject { put("cleared", ok) }
            }
            "net.cookieFlush" -> {
                CookieBridge.flush()
                buildJsonObject { put("flushed", true) }
            }
            "net.resourceUrl" -> {
                val url = params.optString("url", "").trim()
                val port = localMessageBridge.listeningPort.takeIf { it > 0 } ?: localMessageBridge.start()
                JsonPrimitive(WebResourceGateway.buildProxyUrl(port, url, params))
            }

            "pan.play" -> handlePanPlay(params)
            "pan.check" -> driveCheckService.checkFromJson(params)

            "player.playUrl" -> {
                val rawUrl = params.optString("url", params.optString("playUrl", "")).trim()
                // 若传入的是网盘分享链接或 push:// 协议链接，自动走推送源 (push_agent) 解析播放
                if (DriveCheckService.isPanShareUrl(rawUrl)) {
                    handlePanPlay(params)
                } else {
                    val entry = webHomeInlineStore.registerPlayUrl(params)
                    mainHandler.post {
                        callbacks.onNavigateToPlay(
                            WebHomeInlineStore.KEY,
                            entry.vod.vodId,
                            entry.vod.vodName,
                            entry.vod.vodPic,
                        )
                    }
                    buildJsonObject {
                        put("started", true)
                        put("siteKey", WebHomeInlineStore.KEY)
                        put("vodId", entry.vod.vodId)
                    }
                }
            }
            "player.playVodInline" -> {
                val entry = webHomeInlineStore.registerPlayVodInline(params)
                mainHandler.post {
                    callbacks.onNavigateToPlay(
                        WebHomeInlineStore.KEY,
                        entry.vod.vodId,
                        entry.vod.vodName,
                        entry.vod.vodPic,
                    )
                }
                buildJsonObject {
                    put("started", true)
                    put("siteKey", WebHomeInlineStore.KEY)
                    put("vodId", entry.vod.vodId)
                }
            }
            "player.playVod" -> {
                val hasInlineEpisodes = params.containsKey("episodes") || params.containsKey("flags") ||
                    params.containsKey("playFlags") || params.containsKey("vod_play_url") ||
                    params.containsKey("vodPlayUrl") || params.optObj("vod") != null
                if (hasInlineEpisodes) {
                    val entry = webHomeInlineStore.registerPlayVodInline(params)
                    mainHandler.post {
                        callbacks.onNavigateToPlay(
                            WebHomeInlineStore.KEY,
                            entry.vod.vodId,
                            entry.vod.vodName,
                            entry.vod.vodPic,
                        )
                    }
                    buildJsonObject {
                        put("started", true)
                        put("vodId", entry.vod.vodId)
                    }
                } else if (params.containsKey("url") || params.containsKey("playUrl")) {
                    val rawUrl = params.optString("url", params.optString("playUrl", "")).trim()
                    if (DriveCheckService.isPanShareUrl(rawUrl)) {
                        handlePanPlay(params)
                    } else {
                        val entry = webHomeInlineStore.registerPlayUrl(params)
                        mainHandler.post {
                            callbacks.onNavigateToPlay(
                                WebHomeInlineStore.KEY,
                                entry.vod.vodId,
                                entry.vod.vodName,
                                entry.vod.vodPic,
                            )
                        }
                        buildJsonObject {
                            put("started", true)
                            put("vodId", entry.vod.vodId)
                        }
                    }
                } else {
                    val siteKey = params.optString("siteKey", params.optString("site", "")).trim()
                    val rawVodId = params.optString("vodId", params.optString("id", "")).trim()
                    val vodId = if (siteKey == SiteRepository.PUSH_KEY) stripPush(rawVodId) else rawVodId
                    val vodName = params.optString("vodName", params.optString("title", params.optString("name", ""))).trim()
                    val vodPic = params.optString("vodPic", params.optString("pic", "")).trim()
                    require(siteKey.isNotEmpty() && vodId.isNotEmpty()) { "playVod requires siteKey and vodId or inline episodes" }
                    mainHandler.post {
                        callbacks.onNavigateToPlay(siteKey, vodId, vodName, vodPic)
                    }
                    buildJsonObject { put("started", true) }
                }
            }
            "player.preloadArtwork" -> buildJsonObject { put("ok", true) }
            "player.control" -> buildJsonObject { put("ok", true) }
            "player.status" -> buildJsonObject {
                put("playing", false)
                put("position", 0)
                put("duration", 0)
            }
            "player.detail" -> {
                val hasInlineEpisodes = params.containsKey("episodes") || params.containsKey("flags") ||
                    params.containsKey("playFlags") || params.containsKey("vod_play_url") ||
                    params.containsKey("vodPlayUrl") || params.optObj("vod") != null
                if (hasInlineEpisodes) {
                    val entry = webHomeInlineStore.registerPlayVodInline(params)
                    mainHandler.post {
                        callbacks.onNavigateToDetail(
                            WebHomeInlineStore.KEY,
                            entry.vod.vodId,
                            entry.vod.vodName,
                            entry.vod.vodPic,
                        )
                    }
                    buildJsonObject {
                        put("opened", true)
                        put("vodId", entry.vod.vodId)
                    }
                } else {
                    val siteKey = params.optString("siteKey", params.optString("site", "")).trim()
                    val vodId = params.optString("vodId", params.optString("id", "")).trim()
                    val vodName = params.optString("vodName", params.optString("title", params.optString("name", ""))).trim()
                    val vodPic = params.optString("vodPic", params.optString("pic", "")).trim()
                    require(siteKey.isNotEmpty() && vodId.isNotEmpty()) { "detail requires siteKey and vodId" }
                    mainHandler.post {
                        callbacks.onNavigateToDetail(siteKey, vodId, vodName, vodPic)
                    }
                    buildJsonObject { put("opened", true) }
                }
            }
            "player.search", "app.search" -> {
                val keyword = params.optString("keyword", params.optString("wd", params.optString("query", ""))).trim()
                mainHandler.post {
                    callbacks.onNavigateToSearch(keyword)
                }
                buildJsonObject {
                    put("opened", true)
                    put("keyword", keyword)
                }
            }
            "player.stop" -> buildJsonObject { put("stopped", true) }

            "app.openVod", "app.openLive", "app.openSetting" -> buildJsonObject { put("ok", true) }
            "app.history" -> buildJsonArray {}
            "app.toast", "ext.toast" -> {
                val message = params.optString("message", params.optString("text", "")).trim()
                val isLong = params.optString("duration", "short").equals("long", ignoreCase = true)
                if (message.isNotEmpty()) {
                    AppToast.show(message, isLong = isLong)
                }
                buildJsonObject { put("shown", message.isNotEmpty()) }
            }
            "app.clipboardGet" -> {
                val text = readClipboardOnMain()
                buildJsonObject { put("text", text) }
            }
            "app.clipboardSet" -> {
                val text = params.optString("text", params.optString("value", ""))
                writeClipboardOnMain(text)
                buildJsonObject { put("copied", true) }
            }
            "app.openBrowser" -> {
                val url = params.optString("url", "").trim()
                require(CookieBridge.isHttpUrl(url)) { "invalid browser url" }
                mainHandler.post {
                    runCatching {
                        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
                            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        }
                        context.startActivity(intent)
                    }
                }
                buildJsonObject { put("opened", true) }
            }
            "app.openWebView" -> {
                val url = params.optString("url", "").trim()
                require(CookieBridge.isHttpUrl(url)) { "invalid webview url" }
                val options = WebOptions.fromJsonElement(params)
                mainHandler.post {
                    WebActivity.open(context, options)
                }
                buildJsonObject { put("opened", true) }
            }

            "pan.login", "pan.webview" -> {
                val url = resolvePanUrl(params)
                require(CookieBridge.isHttpUrl(url)) { "invalid pan url" }
                val options = WebOptions(url = url, mode = "browser")
                mainHandler.post {
                    WebActivity.open(context, options)
                }
                buildJsonObject {
                    put("opened", true)
                    put("url", url)
                }
            }
            "pan.cookie", "pan.getCookie" -> {
                val url = resolvePanUrl(params)
                val cookie = CookieBridge.get(url)
                buildJsonObject {
                    put("url", url)
                    put("cookie", cookie)
                }
            }
            "pan.setCookie" -> {
                val url = resolvePanUrl(params)
                val cookie = params.optString("cookie", "")
                val ok = CookieBridge.set(url, cookie)
                buildJsonObject {
                    put("saved", ok)
                    put("url", url)
                }
            }
            "pan.clearCookie" -> {
                val url = resolvePanUrl(params)
                val ok = CookieBridge.remove(url, null)
                buildJsonObject {
                    put("cleared", ok)
                    put("url", url)
                }
            }

            "cache.get" -> handleCacheGet(params, pageUrl)
            "cache.set" -> handleCacheSet(params, pageUrl)
            "cache.del", "cache.remove" -> handleCacheRemove(params, pageUrl)
            "cache.clear" -> handleCacheClear(params, pageUrl)
            "cache.keys" -> handleCacheKeys(params, pageUrl)

            "device.info" -> buildJsonObject {
                put("platform", "android")
                put("brand", Build.BRAND.orEmpty())
                put("manufacturer", Build.MANUFACTURER.orEmpty())
                put("model", Build.MODEL.orEmpty())
                put("sdkInt", Build.VERSION.SDK_INT)
                put("release", Build.VERSION.RELEASE.orEmpty())
                put("appId", BuildConfig.APPLICATION_ID)
                put("versionName", BuildConfig.VERSION_NAME)
                put("versionCode", BuildConfig.VERSION_CODE)
                put("isTv", false)
            }

            "site.info" -> {
                val activeUrl = settingsRepository.currentConfigUrl.ifBlank {
                    settingsRepository.configUrlFlow.first()
                }
                val config = if (activeUrl.isNotBlank()) configDao.find(activeUrl) else configDao.latest()
                val sites = siteDao.getAll()
                buildJsonObject {
                    put("key", "webhome")
                    put("id", config?.url ?: pageUrl)
                    put("name", config?.desc ?: "HTML 网页源")
                    put("homePage", config?.url ?: pageUrl)
                    put("url", config?.url ?: pageUrl)
                    put("type", "webpage")
                    put(
                        "sites",
                        buildJsonArray {
                            sites.forEach { s ->
                                add(
                                    buildJsonObject {
                                        put("key", s.key)
                                        put("name", s.name)
                                        put("type", s.type)
                                        put("searchable", s.searchable)
                                        put("changeable", s.changeable)
                                    }
                                )
                            }
                        },
                    )
                }
            }

            "config.info" -> {
                val activeUrl = settingsRepository.currentConfigUrl.ifBlank {
                    settingsRepository.configUrlFlow.first()
                }
                val config = if (activeUrl.isNotBlank()) configDao.find(activeUrl) else configDao.latest()
                val allConfigs = configDao.getAll()
                buildJsonObject {
                    put("id", config?.url.hashCode())
                    put("url", config?.url.orEmpty())
                    put("name", config?.name.orEmpty())
                    put("desc", config?.desc.orEmpty())
                    put("type", if (config?.isHtml == true) "html" else "vod")
                    put("driveCheck", true)
                    put(
                        "configs",
                        buildJsonArray {
                            allConfigs.forEach { c ->
                                add(
                                    buildJsonObject {
                                        put("url", c.url)
                                        put("name", c.name)
                                        put("desc", c.desc)
                                        put("type", if (c.isHtml) "html" else "vod")
                                        put("active", c.url == config?.url)
                                    }
                                )
                            }
                        },
                    )
                }
            }

            "ext.info" -> {
                val activeUrl = settingsRepository.currentConfigUrl.ifBlank {
                    settingsRepository.configUrlFlow.first()
                }
                val config = if (activeUrl.isNotBlank()) configDao.find(activeUrl) else configDao.latest()
                buildJsonObject {
                    put("siteKey", "webhome")
                    put("siteName", config?.desc ?: "HTML 网页源")
                    put("homePage", config?.url ?: pageUrl)
                    put("enabled", false)
                    put("matched", 0)
                    put("ready", 0)
                }
            }
            "ext.log" -> {
                Timber.d("webhome-ext: message=%s data=%s", params.optString("message", ""), params["data"])
                buildJsonObject { put("ok", true) }
            }
            "ext.capabilities" -> buildJsonObject {
                put("version", "1.0.0")
                put(
                    "namespaces",
                    buildJsonArray {
                        listOf("app", "net", "player", "pan", "cache", "device", "site", "config", "ext", "ui", "navigation")
                            .forEach { add(JsonPrimitive(it)) }
                    },
                )
                put("webResource", true)
                put("inlinePlay", true)
                put("driveCheck", true)
            }
            "ext.invoke", "ext.emit" -> buildJsonObject { put("ok", true) }

            "ui.setToolbar" -> {
                val visible = params.optBoolean("visible", true)
                val current = callbacks.getChromeOptions()
                val updated = current.copy(floatingControls = visible)
                mainHandler.post { callbacks.onUpdateChrome(updated) }
                updated.toJson()
            }
            "ui.setChrome" -> {
                val current = callbacks.getChromeOptions()
                val updated = WebHomeChromeOptions(
                    bottomTabInset = params.optBoolean("bottomTabInset", current.bottomTabInset),
                    floatingControls = params.optBoolean("floatingControls", current.floatingControls),
                    edgeHandle = params.optBoolean("edgeHandle", current.edgeHandle),
                )
                mainHandler.post { callbacks.onUpdateChrome(updated) }
                updated.toJson()
            }
            "ui.restoreChrome" -> {
                val restored = WebHomeChromeOptions()
                mainHandler.post { callbacks.onUpdateChrome(restored) }
                restored.toJson()
            }
            "ui.getChrome" -> callbacks.getChromeOptions().toJson()
            "ui.getViewport" -> callbacks.getViewport().toJson()

            "navigation.back" -> {
                mainHandler.post { callbacks.onNavigateBack() }
                buildJsonObject { put("ok", true) }
            }
            "navigation.reload" -> {
                mainHandler.post { callbacks.onReloadPage() }
                buildJsonObject { put("ok", true) }
            }
            "navigation.setBackHandler" -> {
                val enabled = params.optBoolean("enabled", true)
                mainHandler.post { callbacks.onSetJsBackHandler(enabled) }
                buildJsonObject { put("enabled", enabled) }
            }
            "navigation.clearBackHandler" -> {
                mainHandler.post { callbacks.onSetJsBackHandler(false) }
                buildJsonObject { put("enabled", false) }
            }

            else -> throw IllegalArgumentException("Unsupported bridge action: $action")
        }
    }

    private fun handlePanPlay(params: JsonObject): JsonObject {
        val rawUrl = params.optString("url", params.optString("playUrl", "")).trim()
        val playUrl = stripPush(rawUrl)
        require(playUrl.isNotEmpty()) { "url不能为空" }
        val title = params.optString("title", params.optString("vodName", params.optString("name", ""))).trim()
        val pic = params.optString("pic", params.optString("vodPic", "")).trim()
        val playTitle = title.ifEmpty { playUrl }
        Timber.i("HTML 网页触发网盘推送播放: route=%s, title=%s, url=%s", SiteRepository.PUSH_KEY, playTitle, playUrl)
        mainHandler.post {
            callbacks.onNavigateToPlay(SiteRepository.PUSH_KEY, playUrl, playTitle, pic)
        }
        return buildJsonObject {
            put("started", true)
            put("siteKey", SiteRepository.PUSH_KEY)
            put("vodId", playUrl)
        }
    }

    private fun resolvePanUrl(params: JsonObject): String {
        val explicitUrl = params.optString("url", "").trim()
        if (CookieBridge.isHttpUrl(explicitUrl)) return explicitUrl
        val type = params.optString("type", params.optString("pan", "")).trim().lowercase(Locale.ROOT)
        if (CookieBridge.isHttpUrl(type)) return type
        return PAN_DEFAULT_URLS[type] ?: explicitUrl
    }

    private fun originNamespace(pageUrl: String): String {
        return runCatching {
            val uri = Uri.parse(pageUrl)
            val scheme = uri.scheme?.lowercase(Locale.ROOT).orEmpty()
            val host = uri.host?.lowercase(Locale.ROOT).orEmpty()
            val port = uri.port
            if (port > 0) "$scheme://$host:$port" else "$scheme://$host"
        }.getOrDefault("default")
    }

    private fun buildScopedCacheKey(params: JsonObject, pageUrl: String): String {
        val rule = params.optString("rule", "").trim()
        val key = params.optString("key", "").trim()
        val fullKey = if (rule.isNotEmpty()) "${rule}_$key" else key
        return "${originNamespace(pageUrl)}::$fullKey"
    }

    private fun handleCacheGet(params: JsonObject, pageUrl: String): JsonElement {
        val key = params.optString("key", "").trim()
        val defaultValue = params["defaultValue"] ?: JsonPrimitive("")
        if (key.isEmpty()) return defaultValue
        val prefs = context.getSharedPreferences(CACHE_PREFS_NAME, Context.MODE_PRIVATE)
        val scopedKey = buildScopedCacheKey(params, pageUrl)
        val raw = prefs.getString(scopedKey, null) ?: return defaultValue
        val wrapper = ResultParser.parseJson(raw) as? JsonObject ?: return JsonPrimitive(raw)
        val expireAt = wrapper.optLong("expireAt", 0L)
        if (expireAt in 1..System.currentTimeMillis()) {
            prefs.edit().remove(scopedKey).apply()
            return defaultValue
        }
        return wrapper["value"] ?: defaultValue
    }

    private fun handleCacheSet(params: JsonObject, pageUrl: String): JsonObject {
        val key = params.optString("key", "").trim()
        require(key.isNotEmpty()) { "cache.set requires non-empty key" }
        val value = params["value"] ?: JsonNull
        val ttlMs = params.optLong("ttl", 0L)
        val expireAt = if (ttlMs > 0L) System.currentTimeMillis() + ttlMs else 0L
        val wrapper = buildJsonObject {
            put("value", value)
            put("expireAt", expireAt)
            put("updatedAt", System.currentTimeMillis())
        }
        val scopedKey = buildScopedCacheKey(params, pageUrl)
        context.getSharedPreferences(CACHE_PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(scopedKey, wrapper.toString())
            .apply()
        return buildJsonObject { put("saved", true) }
    }

    private fun handleCacheRemove(params: JsonObject, pageUrl: String): JsonObject {
        val key = params.optString("key", "").trim()
        if (key.isNotEmpty()) {
            val scopedKey = buildScopedCacheKey(params, pageUrl)
            context.getSharedPreferences(CACHE_PREFS_NAME, Context.MODE_PRIVATE)
                .edit()
                .remove(scopedKey)
                .apply()
        }
        return buildJsonObject { put("removed", true) }
    }

    private fun handleCacheClear(params: JsonObject, pageUrl: String): JsonObject {
        val prefix = params.optString("prefix", "").trim()
        val nsPrefix = "${originNamespace(pageUrl)}::$prefix"
        val prefs = context.getSharedPreferences(CACHE_PREFS_NAME, Context.MODE_PRIVATE)
        val editor = prefs.edit()
        var count = 0
        for (k in prefs.all.keys) {
            if (k.startsWith(nsPrefix)) {
                editor.remove(k)
                count++
            }
        }
        editor.apply()
        return buildJsonObject { put("cleared", count) }
    }

    private fun handleCacheKeys(params: JsonObject, pageUrl: String) = buildJsonArray {
        val prefix = params.optString("prefix", "").trim()
        val ns = "${originNamespace(pageUrl)}::"
        val fullPrefix = "$ns$prefix"
        val prefs = context.getSharedPreferences(CACHE_PREFS_NAME, Context.MODE_PRIVATE)
        for (k in prefs.all.keys) {
            if (k.startsWith(fullPrefix)) {
                add(JsonPrimitive(k.removePrefix(ns)))
            }
        }
    }

    private suspend fun awaitPageUrl(): String = suspendCancellableCoroutine { cont ->
        mainHandler.post {
            val url = runCatching { callbacks.getCurrentPageUrl() }.getOrDefault("")
            if (cont.isActive) cont.resume(url)
        }
    }

    private suspend fun readClipboardOnMain(): String = suspendCancellableCoroutine { cont ->
        mainHandler.post {
            val text = runCatching {
                val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                val item = cm?.primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0)
                item?.coerceToText(context)?.toString().orEmpty()
            }.getOrDefault("")
            if (cont.isActive) cont.resume(text)
        }
    }

    private suspend fun writeClipboardOnMain(text: String) = suspendCancellableCoroutine { cont ->
        mainHandler.post {
            runCatching {
                val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                cm?.setPrimaryClip(ClipData.newPlainText("fongmi", text))
            }
            if (cont.isActive) cont.resume(Unit)
        }
    }

    private fun isOriginAllowed(pageUrl: String): Boolean {
        val trimmed = pageUrl.trim()
        return CookieBridge.isHttpUrl(trimmed) || trimmed.startsWith("file://", ignoreCase = true)
    }

    private fun deliverSuccess(callbackId: String, data: Any?) {
        val envelope = buildJsonObject {
            put("ok", true)
            put("data", data.toJsonElement())
        }
        deliverEnvelope(callbackId, envelope)
    }

    private fun deliverError(callbackId: String, message: String, code: Int) {
        val envelope = buildJsonObject {
            put("ok", false)
            put("error", message)
            put("code", code)
        }
        deliverEnvelope(callbackId, envelope)
    }

    private fun deliverEnvelope(callbackId: String, envelope: JsonObject) {
        val serialized = envelope.toString()
        storeResult(callbackId, serialized)
        val safeId = quoteJsString(callbackId)
        val script = "window.fongmi && window.fongmi.__onCallback && window.fongmi.__onCallback($safeId);"
        mainHandler.post {
            callbacks.evaluateJs(script)
        }
    }

    private fun storeResult(id: String, payload: String) {
        if (resultStore.size >= MAX_RESULTS_BUFFER) {
            val oldest = resultOrder.entries.minByOrNull { it.value }?.key
            if (oldest != null) {
                resultStore.remove(oldest)
                resultOrder.remove(oldest)
            }
        }
        resultStore[id] = payload
        resultOrder[id] = System.currentTimeMillis()
    }
}
