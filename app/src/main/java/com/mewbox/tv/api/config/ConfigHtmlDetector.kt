package com.mewbox.tv.api.config

/**
 * 本地路径与 HTML 网页源检测工具（从 [VodConfig] 拆出的纯静态判定逻辑）。
 */
internal object ConfigHtmlDetector {

    private val HTML_TITLE_REGEX = Regex(
        pattern = "<title[^>]*>(.*?)</title>",
        options = setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL),
    )
    private val WHITESPACE_REGEX = Regex("\\s+")

    /** 判断指定 URL 是否指向本地文件或应用私有本地点播归档 */
    fun isLocalFile(url: String): Boolean {
        val trimmed = url.trim()
        return trimmed.startsWith("file://", ignoreCase = true) ||
            trimmed.startsWith("content://", ignoreCase = true) ||
            trimmed.startsWith("local://vod/", ignoreCase = true) ||
            trimmed.startsWith("/")
    }

    /** 判断指定 URL 是否以 HTML 后缀结尾 */
    fun isHtmlFile(url: String): Boolean {
        val clean = url.substringBefore('?').substringBefore('#').trim()
        return clean.endsWith(".html", ignoreCase = true) || clean.endsWith(".htm", ignoreCase = true)
    }

    /** 判定响应 Content-Type 或正文是否为 HTML 网页源 */
    fun isHtmlResponse(contentType: String, body: String): Boolean {
        val trimmed = body.trimStart('\uFEFF', ' ', '\t', '\r', '\n')
        if (trimmed.isEmpty()) return false
        if (trimmed.startsWith("{") || trimmed.startsWith("[")) return false

        val ctLower = contentType.lowercase()
        if ((ctLower.contains("text/html") || ctLower.contains("application/xhtml+xml")) && trimmed.startsWith("<")) {
            return true
        }

        val headSample = trimmed.take(2048).lowercase()
        if (
            headSample.startsWith("<!doctype html") ||
            headSample.startsWith("<html") ||
            headSample.startsWith("<head") ||
            headSample.startsWith("<body") ||
            headSample.startsWith("<meta") ||
            headSample.startsWith("<title") ||
            headSample.startsWith("<script")
        ) {
            return true
        }

        if (headSample.startsWith("<!--") && (headSample.contains("<html") || headSample.contains("<!doctype html"))) {
            return true
        }

        return headSample.contains("<html") &&
            (body.contains("</html>", ignoreCase = true) || headSample.contains("<head") || headSample.contains("<body"))
    }

    /** 提取 HTML `<title>` 文本作为默认订阅展示名称 */
    fun extractHtmlTitle(body: String): String {
        if (body.isBlank()) return ""
        val raw = HTML_TITLE_REGEX.find(body)?.groupValues?.getOrNull(1) ?: return ""
        return raw
            .replace("&nbsp;", " ", ignoreCase = true)
            .replace("&amp;", "&", ignoreCase = true)
            .replace("&lt;", "<", ignoreCase = true)
            .replace("&gt;", ">", ignoreCase = true)
            .replace("&quot;", "\"", ignoreCase = true)
            .replace("&#39;", "'", ignoreCase = true)
            .replace("&apos;", "'", ignoreCase = true)
            .replace(WHITESPACE_REGEX, " ")
            .trim()
            .take(60)
    }
}
