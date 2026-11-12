package com.mewbox.tv.api.net

import android.net.Uri
import android.webkit.CookieManager
import okhttp3.Response
import timber.log.Timber

/**
 * WebView CookieManager 与 OkHttp 双向 Cookie 同步及请求头清洗工具（严格对齐 MewPlayer CookieBridge + HeaderPolicy）。
 */
object CookieBridge {

    fun isHttpUrl(url: String?): Boolean {
        val text = url?.trim().orEmpty()
        return text.startsWith("http://", ignoreCase = true) || text.startsWith("https://", ignoreCase = true)
    }

    fun get(url: String?): String {
        val target = url?.trim().orEmpty()
        if (!isHttpUrl(target)) return ""
        return try {
            val value = CookieManager.getInstance().getCookie(target)
            if (value == null || value.contains('\r') || value.contains('\n')) "" else value
        } catch (e: Exception) {
            Timber.w(e, "读取 Cookie 失败: %s", target)
            ""
        }
    }

    fun set(url: String?, cookie: String?): Boolean {
        val target = url?.trim().orEmpty()
        val raw = cookie?.trim().orEmpty()
        if (!isHttpUrl(target) || raw.isEmpty() || raw.contains('\r') || raw.contains('\n')) return false
        return try {
            val manager = CookieManager.getInstance()
            manager.setAcceptCookie(true)
            manager.setCookie(target, raw)
            manager.flush()
            true
        } catch (e: Exception) {
            Timber.w(e, "写入 Cookie 失败: %s", target)
            false
        }
    }

    fun remove(url: String?, name: String?): Boolean {
        val target = url?.trim().orEmpty()
        if (!isHttpUrl(target)) return false
        return try {
            val manager = CookieManager.getInstance()
            manager.setAcceptCookie(true)
            val cookieName = name?.trim().orEmpty()
            if (cookieName.isNotEmpty()) {
                manager.setCookie(target, "$cookieName=; Max-Age=0")
                val path = runCatching { Uri.parse(target).path }.getOrNull()
                if (!path.isNullOrEmpty() && path != "/") {
                    manager.setCookie(target, "$cookieName=; Path=$path; Max-Age=0")
                }
            } else {
                val raw = manager.getCookie(target).orEmpty()
                raw.split(";").forEach { part ->
                    val trimmed = part.trim()
                    val eq = trimmed.indexOf('=')
                    val key = if (eq > 0) trimmed.substring(0, eq).trim() else trimmed
                    if (key.isNotEmpty()) {
                        manager.setCookie(target, "$key=; Max-Age=0")
                    }
                }
            }
            manager.flush()
            true
        } catch (e: Exception) {
            Timber.w(e, "删除 Cookie 失败: %s", target)
            false
        }
    }

    fun clearAll(): Boolean {
        return try {
            val manager = CookieManager.getInstance()
            manager.removeAllCookies(null)
            manager.flush()
            true
        } catch (e: Exception) {
            Timber.w(e, "清空全部 Cookie 失败")
            false
        }
    }

    fun flush() {
        try {
            CookieManager.getInstance().flush()
        } catch (e: Exception) {
            Timber.w(e, "刷新 CookieManager 失败")
        }
    }

    fun attachCookieHeader(headers: MutableMap<String, String>, url: String) {
        if (!isHttpUrl(url)) return
        if (headers.keys.any { it.equals("Cookie", ignoreCase = true) }) return
        val cookie = get(url)
        if (cookie.isNotEmpty()) {
            headers["Cookie"] = cookie
        }
    }

    fun storeResponseCookies(response: Response, fallbackUrl: String) {
        val targetUrl = response.request.url.toString().ifBlank { fallbackUrl }
        storeSetCookieHeaders(response.headers("Set-Cookie"), targetUrl)
    }

    fun storeSetCookieHeaders(setCookies: List<String>, targetUrl: String) {
        if (setCookies.isEmpty() || !isHttpUrl(targetUrl)) return
        try {
            val manager = CookieManager.getInstance()
            manager.setAcceptCookie(true)
            var wrote = false
            for (cookie in setCookies) {
                val trimmed = cookie.trim()
                if (trimmed.isEmpty() || trimmed.contains('\r') || trimmed.contains('\n')) continue
                manager.setCookie(targetUrl, trimmed)
                wrote = true
            }
            if (wrote) manager.flush()
        } catch (e: Exception) {
            Timber.w(e, "回写 Set-Cookie 失败: %s", targetUrl)
        }
    }

    fun sanitizeHeaderName(name: String?): String? {
        val clean = name?.trim().orEmpty()
        if (clean.isEmpty()) return null
        for (ch in clean) {
            if (ch == '\r' || ch == '\n' || ch <= ' ' || ch >= '\u007f' || ch == ':') return null
        }
        return clean
    }

    fun sanitizeHeaderValue(value: String?): String? {
        val clean = value?.trim().orEmpty()
        if (clean.contains('\r') || clean.contains('\n')) return null
        return clean
    }

    fun sanitizeHeaders(raw: Map<String, String>?): MutableMap<String, String> {
        val result = LinkedHashMap<String, String>()
        if (raw.isNullOrEmpty()) return result
        for ((k, v) in raw) {
            val name = sanitizeHeaderName(k) ?: continue
            val value = sanitizeHeaderValue(v) ?: continue
            result[name] = value
        }
        return result
    }
}

