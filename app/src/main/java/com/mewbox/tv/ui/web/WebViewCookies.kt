package com.mewbox.tv.ui.web

import android.webkit.CookieManager
import timber.log.Timber

/**
 * Cookie 管理工具类（严格对齐 FongMi WebViewCookies）
 */
object WebViewCookies {

    fun get(url: String): String {
        if (!isHttp(url)) return ""
        return try {
            val value = CookieManager.getInstance().getCookie(url)
            if (value == null || value.contains('\r') || value.contains('\n')) "" else value
        } catch (e: Exception) {
            Timber.e(e, "获取 Cookie 异常: $url")
            ""
        }
    }

    fun set(url: String, cookie: String): Boolean {
        if (!isHttp(url) || cookie.isBlank() || cookie.contains('\r') || cookie.contains('\n')) return false
        return try {
            val manager = CookieManager.getInstance()
            manager.setAcceptCookie(true)
            manager.setCookie(url, cookie)
            manager.flush()
            true
        } catch (e: Exception) {
            Timber.e(e, "写入 Cookie 异常: $url")
            false
        }
    }

    private fun isHttp(url: String): Boolean =
        url.startsWith("http://", ignoreCase = true) || url.startsWith("https://", ignoreCase = true)
}

