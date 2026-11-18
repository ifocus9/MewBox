package com.fongmi.nodejs

import java.io.IOException
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.util.Locale
import java.util.regex.Pattern

/**
 * Node.js 源地址定位与分类器：
 * 统一识别 `local://node/<id>`、`.zip` 压缩包、`index.js.md5` 与 `index.js` 入口，
 * 并负责衍生伴生文件地址与 MD5 sidecar 解析。
 */
internal object NodeSourceLocator {

    const val LOCAL_NODE_SCHEME = "local://node/"

    private val MD5_PATTERN = Pattern.compile("(?i)(?:^|\\s)([a-f0-9]{32})(?:\\s|$)")

    fun isLocalNodeSource(source: String?): Boolean {
        return source?.trim()?.startsWith(LOCAL_NODE_SCHEME, ignoreCase = true) == true
    }

    fun isHttpSource(source: String?): Boolean {
        val trimmed = source?.trim().orEmpty()
        return trimmed.startsWith("http://", ignoreCase = true) ||
            trimmed.startsWith("https://", ignoreCase = true)
    }

    fun isZip(source: String?): Boolean {
        if (source.isNullOrBlank()) return false
        return cleanPath(source).lowercase(Locale.ROOT).endsWith(".zip")
    }

    fun isConfig(url: String?): Boolean {
        if (url.isNullOrBlank()) return false
        val trimmed = url.trim()
        if (trimmed.regionMatches(0, "content://", 0, 10, ignoreCase = true)) return false
        if (isLocalNodeSource(trimmed)) return true
        val lower = cleanPath(trimmed).lowercase(Locale.ROOT)
        return lower.endsWith(".zip") ||
            lower.endsWith("/index.js.md5") ||
            lower == "index.js.md5" ||
            lower.endsWith("/index.js") ||
            lower == "index.js"
    }

    /** 将直接填写的 `.../index.js` 入口归一化为伴生 `.../index.js.md5`，原样保留查询参数与锚点。 */
    @Throws(IOException::class)
    fun normalizeMd5Source(source: String): String {
        val (path, suffix) = splitPathAndSuffix(source.trim())
        val lower = decodeSafely(path).lowercase(Locale.ROOT)
        if (lower.endsWith("/index.js.md5") || lower == "index.js.md5") {
            return source.trim()
        }
        if (lower.endsWith("/index.js") || lower == "index.js") {
            return companion(source.trim(), "index.js.md5")
        }
        throw IOException("Node config must end with index.js.md5, index.js or .zip")
    }

    @Throws(IOException::class)
    fun companion(source: String, name: String): String {
        val (path, suffix) = splitPathAndSuffix(source)
        val slash = path.lastIndexOf('/')
        if (slash < 0) {
            if (source == "index.js" || source == "index.js.md5") {
                return name + suffix
            }
            throw IOException("Node config URL has no parent path")
        }
        return path.substring(0, slash + 1) + name + suffix
    }

    fun bundleKey(source: String): String = Crypto.sha256(source.trim())

    @Throws(IOException::class)
    fun parseMd5(text: String?): String {
        val matcher = MD5_PATTERN.matcher(text?.trim().orEmpty())
        if (!matcher.find()) throw IOException("Invalid MD5 sidecar: $text")
        return matcher.group(1)?.lowercase(Locale.ROOT) ?: throw IOException("Invalid MD5 match")
    }

    private fun cleanPath(source: String): String {
        val rawPath = source.trim().split(Regex("[?#]"), 2)[0]
        return decodeSafely(rawPath)
    }

    private fun splitPathAndSuffix(source: String): Pair<String, String> {
        var end = source.length
        val query = source.indexOf('?')
        val fragment = source.indexOf('#')
        if (query >= 0) end = minOf(end, query)
        if (fragment >= 0) end = minOf(end, fragment)
        return source.substring(0, end) to source.substring(end)
    }

    private fun decodeSafely(value: String): String {
        return try {
            URLDecoder.decode(value, StandardCharsets.UTF_8.name())
        } catch (_: Exception) {
            value
        }
    }
}
