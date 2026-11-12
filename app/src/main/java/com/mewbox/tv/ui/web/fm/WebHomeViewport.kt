package com.mewbox.tv.ui.web.fm

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * 网页视口安全区与底部内边距快照（对齐 MewPlayer WebHomeViewport）。
 *
 * 将状态栏、手势导航栏、刘海屏安全区转换为 CSS 像素，注入 `--fm-safe-*` CSS 变量并派发 `fmviewport` 事件。
 */
data class WebHomeViewport(
    val safeTopPx: Int = 0,
    val safeRightPx: Int = 0,
    val safeBottomPx: Int = 0,
    val safeLeftPx: Int = 0,
    val bottomUiInsetPx: Int = 0,
) {
    val safeBottomTotalPx: Int
        get() = safeBottomPx + bottomUiInsetPx

    fun toJson(): JsonObject = buildJsonObject {
        put("safeTop", safeTopPx)
        put("safeRight", safeRightPx)
        put("safeBottom", safeBottomPx)
        put("safeLeft", safeLeftPx)
        put("safeBottomMax", safeBottomTotalPx)
        put("statusBarHeight", safeTopPx)
        put("navigationBarHeight", safeBottomPx)
        put("bottomUiInset", bottomUiInsetPx)
        put("safeBottomTotal", safeBottomTotalPx)
        put("chromeMode", "edge")
        put("systemBarsHidden", false)
    }

    fun buildInjectionScript(): String {
        val json = toJson().toString()
        return """
            (function() {
                try {
                    var vp = $json;
                    var root = document.documentElement;
                    if (root && root.style) {
                        root.style.setProperty('--fm-safe-top', vp.safeTop + 'px');
                        root.style.setProperty('--fm-safe-right', vp.safeRight + 'px');
                        root.style.setProperty('--fm-safe-bottom', vp.safeBottom + 'px');
                        root.style.setProperty('--fm-safe-left', vp.safeLeft + 'px');
                        root.style.setProperty('--fm-safe-bottom-max', vp.safeBottomMax + 'px');
                        root.style.setProperty('--fm-status-bar-height', vp.statusBarHeight + 'px');
                        root.style.setProperty('--fm-navigation-bar-height', vp.navigationBarHeight + 'px');
                        root.style.setProperty('--fm-bottom-ui-inset', vp.bottomUiInset + 'px');
                        root.style.setProperty('--fm-safe-bottom-total', vp.safeBottomTotal + 'px');
                        root.style.setProperty('--fm-chrome-mode', 'edge');
                        root.style.setProperty('--fm-system-bars-hidden', '0');
                    }
                    window.__fmViewport = vp;
                    if (window.fongmi) {
                        window.fongmi._viewport = vp;
                    }
                    window.dispatchEvent(new CustomEvent('fmviewport', { detail: vp }));
                } catch (e) {}
            })();
        """.trimIndent()
    }
}
