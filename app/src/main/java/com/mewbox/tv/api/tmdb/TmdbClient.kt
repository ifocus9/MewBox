package com.mewbox.tv.api.tmdb

import com.mewbox.tv.api.tmdb.bean.TmdbDetail
import com.mewbox.tv.api.tmdb.bean.TmdbMedia
import com.mewbox.tv.api.tmdb.bean.TmdbResult
import com.mewbox.tv.bean.TmdbConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * TMDB API 客户端
 * 提供 API 连通性测试、图片 CDN 测试、片名清洗与元数据查询。
 */
@Singleton
class TmdbClient @Inject constructor(
    private val repository: TmdbRepository,
) {
    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .build()

    private val testClient = OkHttpClient.Builder()
        .connectTimeout(6, TimeUnit.SECONDS)
        .readTimeout(6, TimeUnit.SECONDS)
        .callTimeout(8, TimeUnit.SECONDS)
        .build()

    private val json = Json {
        ignoreUnknownKeys = true
        coerceInputValues = true
        isLenient = true
    }

    /**
     * 测试 API 连通性（请求 /3/configuration 并检查响应码）。
     */
    suspend fun testConnectivity(apiKey: String, apiBase: String): Result<String> = withContext(Dispatchers.IO) {
        val key = apiKey.trim()
        if (key.isEmpty()) {
            return@withContext Result.failure(IllegalArgumentException("请先输入 API Key 或 Token"))
        }
        val base = normalizeApiBase(apiBase)
        val fullUrl = "$base/3/configuration"
        val parsed = fullUrl.toHttpUrlOrNull()
            ?: return@withContext Result.failure(IllegalArgumentException("API 地址格式无效: $base"))

        val urlBuilder = parsed.newBuilder()
        val requestBuilder = Request.Builder()
        applyAuth(requestBuilder, urlBuilder, key)
        requestBuilder.url(urlBuilder.build())

        try {
            testClient.newCall(requestBuilder.build()).execute().use { response ->
                if (response.isSuccessful) {
                    Result.success("API 连接正常 ✓")
                } else {
                    val code = response.code
                    val errorMsg = when (code) {
                        401 -> "HTTP 401: Key 或 Token 无效或未激活"
                        404 -> "HTTP 404: 代理路径错误（未找到 /3/configuration）"
                        else -> "HTTP $code（代理或服务返回异常）"
                    }
                    Result.failure(IOException(errorMsg))
                }
            }
        } catch (e: Exception) {
            val msg = when (e) {
                is SocketTimeoutException -> "连接超时（8s）。若在大陆直连，请配置 TMDB 代理地址"
                is UnknownHostException -> "无法解析域名，请检查代理地址是否正确"
                else -> "连接失败: ${e.message ?: e.javaClass.simpleName}"
            }
            Result.failure(IOException(msg, e))
        }
    }

    /**
     * 测试图片 CDN 连通性（请求测试海报并检查响应码）。
     */
    suspend fun testImageCdn(imageBase: String): Result<String> = withContext(Dispatchers.IO) {
        val base = normalizeImageBase(imageBase)
        val testUrl = "$base/w92/9cqNxx0GxF0bflZmeSMuL5tnGzr.jpg"
        val parsed = testUrl.toHttpUrlOrNull()
            ?: return@withContext Result.failure(IllegalArgumentException("图片 CDN 地址格式无效: $base"))

        val request = Request.Builder().url(parsed).build()
        try {
            testClient.newCall(request).execute().use { response ->
                if (response.isSuccessful) {
                    Result.success("图片 CDN 正常 ✓")
                } else {
                    Result.failure(IOException("图片 CDN 返回 HTTP ${response.code}"))
                }
            }
        } catch (e: Exception) {
            val msg = when (e) {
                is SocketTimeoutException -> "图片 CDN 连接超时（8s）。建议配置图片代理"
                is UnknownHostException -> "图片域名无法解析，请检查配置"
                else -> "图片 CDN 连接失败: ${e.message ?: e.javaClass.simpleName}"
            }
            Result.failure(IOException(msg, e))
        }
    }

    /**
     * 多类型搜索（movie + tv）
     */
    suspend fun searchMulti(rawTitle: String): Result<TmdbResult> = withContext(Dispatchers.IO) {
        val config = repository.getTmdbConfig()
        if (!config.enable || config.apiKey.isBlank()) {
            return@withContext Result.failure(IllegalStateException("TMDB 未开启或未配置 API Key"))
        }
        val cleaned = cleanTitle(rawTitle).ifBlank { rawTitle.trim() }
        if (cleaned.isBlank()) {
            return@withContext Result.failure(IllegalArgumentException("片名为空"))
        }
        val base = normalizeApiBase(config.apiBase)
        val parsed = "$base/3/search/multi".toHttpUrlOrNull()
            ?: return@withContext Result.failure(IllegalArgumentException("TMDB API 基础地址格式无效"))

        val urlBuilder = parsed.newBuilder()
            .addQueryParameter("query", cleaned)
            .addQueryParameter("language", "zh-CN")
            .addQueryParameter("page", "1")
        val requestBuilder = Request.Builder()
        applyAuth(requestBuilder, urlBuilder, config.apiKey)
        requestBuilder.url(urlBuilder.build())

        try {
            client.newCall(requestBuilder.build()).execute().use { response ->
                val body = response.body?.string().orEmpty()
                if (!response.isSuccessful) {
                    return@withContext Result.failure(IOException("HTTP ${response.code}: $body"))
                }
                val result = json.decodeFromString<TmdbResult>(body)
                Result.success(result)
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * 查询详情（含演职员 credits、images 等）
     */
    suspend fun fetchDetail(mediaType: String, tmdbId: Int): Result<TmdbDetail> = withContext(Dispatchers.IO) {
        val config = repository.getTmdbConfig()
        if (!config.enable || config.apiKey.isBlank()) {
            return@withContext Result.failure(IllegalStateException("TMDB 未开启或未配置 API Key"))
        }
        val base = normalizeApiBase(config.apiBase)
        val parsed = "$base/3/$mediaType/$tmdbId".toHttpUrlOrNull()
            ?: return@withContext Result.failure(IllegalArgumentException("TMDB API 基础地址格式无效"))

        val urlBuilder = parsed.newBuilder()
            .addQueryParameter("append_to_response", "credits,images,recommendations,similar")
            .addQueryParameter("include_image_language", "zh,null,en")
            .addQueryParameter("language", "zh-CN")
        val requestBuilder = Request.Builder()
        applyAuth(requestBuilder, urlBuilder, config.apiKey)
        requestBuilder.url(urlBuilder.build())

        try {
            client.newCall(requestBuilder.build()).execute().use { response ->
                val body = response.body?.string().orEmpty()
                if (!response.isSuccessful) {
                    return@withContext Result.failure(IOException("HTTP ${response.code}: $body"))
                }
                val detail = json.decodeFromString<TmdbDetail>(body)
                Result.success(detail)
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    companion object {
        const val DEFAULT_API_BASE = "https://api.tmdb.org"
        const val DEFAULT_IMAGE_BASE = "https://image.tmdb.org/t/p"

        /**
         * 规范化 API Base URL（去除多余斜杠、补充协议头、去除结尾的 /3）
         */
        fun normalizeApiBase(raw: String?): String {
            if (raw == null) return DEFAULT_API_BASE
            var s = raw.trim()
            if (s.isEmpty()) return DEFAULT_API_BASE
            if (!s.startsWith("http://") && !s.startsWith("https://")) {
                s = "https://$s"
            }
            while (s.endsWith("/")) {
                s = s.substring(0, s.length - 1)
            }
            if (s.endsWith("/3")) {
                s = s.substring(0, s.length - 2)
            }
            while (s.endsWith("/")) {
                s = s.substring(0, s.length - 1)
            }
            return if (s.isEmpty()) DEFAULT_API_BASE else s
        }

        /**
         * 规范化图片 CDN Base URL（去除多余斜杠、补充协议头、确保 tmdb 官方域名带 /t/p）
         */
        fun normalizeImageBase(raw: String?): String {
            if (raw == null) return DEFAULT_IMAGE_BASE
            var s = raw.trim()
            if (s.isEmpty()) return DEFAULT_IMAGE_BASE
            if (!s.startsWith("http://") && !s.startsWith("https://")) {
                s = "https://$s"
            }
            while (s.endsWith("/")) {
                s = s.substring(0, s.length - 1)
            }
            if (!s.endsWith("/t/p") && (s.contains("themoviedb.org") || s.contains("tmdb.org"))) {
                s = "$s/t/p"
            }
            return if (s.isEmpty()) DEFAULT_IMAGE_BASE else s
        }

        /**
         * 拼装图片完整 URL
         */
        fun imageUrl(base: String? = null, path: String?, size: String = "w780"): String {
            if (path.isNullOrEmpty()) return ""
            return "${normalizeImageBase(base)}/$size$path"
        }

        /**
         * 判断是否为 TMDB v4 读访问令牌（Read Access Token）
         */
        fun isBearerToken(keyOrToken: String?): Boolean {
            if (keyOrToken == null) return false
            val s = keyOrToken.trim()
            if (s.startsWith("bearer ", ignoreCase = true)) return true
            return s.startsWith("ey") || s.length > 40
        }

        /**
         * 规范化 Bearer Token 内容（去除多余的前缀 Bearer）
         */
        fun extractBearerToken(keyOrToken: String?): String {
            if (keyOrToken == null) return ""
            var s = keyOrToken.trim()
            if (s.startsWith("bearer ", ignoreCase = true)) {
                s = s.substring(7).trim()
            }
            return s
        }

        /**
         * 为请求应用鉴权配置
         */
        fun applyAuth(requestBuilder: Request.Builder, urlBuilder: HttpUrl.Builder, keyOrToken: String?) {
            if (keyOrToken.isNullOrBlank()) return
            val trimmed = keyOrToken.trim()
            if (isBearerToken(trimmed)) {
                requestBuilder.header("Authorization", "Bearer ${extractBearerToken(trimmed)}")
            } else {
                urlBuilder.addQueryParameter("api_key", trimmed)
            }
        }

        /**
         * 片名清洗
         */
        fun cleanTitle(raw: String?): String {
            if (raw.isNullOrEmpty()) return raw.orEmpty()
            var s = raw.trim()

            // 1. 去除末尾或中间括号内容（完结、粤语、国语等）
            s = s.replace(Regex("[（(][^）)]{0,20}[）)]"), "").trim()

            // 2. 去除"更新至第xx集"、"全xx集"、"共xx集"等
            s = s.replace(Regex("(?i)\\s*(更新至|更新到|连载至|更新|全|共)?\\s*(第)?\\s*\\d+\\s*(集|话|ep|episode|期).*$"), "").trim()

            // 3. 去除画质及后续技术标签：4K、2K、2160P、1080P、720P、HDR、HEVC、H265、BluRay 等
            s = s.replace(Regex("(?i)\\s*(4K|2K|2160[pP]|1080[pP]|720[pP]|480[pP]|HDR10?\\+?|Dolby|HEVC|H\\.?265|H\\.?264|BD|BluRay|WEB-?DL|REMUX|x265|x264).*$"), "").trim()

            // 4. 去除语言标签：国语版、粤语版、普通话、中字等
            s = s.replace(Regex("(?i)\\s*(国语|普通话|粤语|中字|字幕|英语|日语|韩语)(版|字幕)?.*$"), "").trim()

            // 5. 去除"完结"标签
            s = s.replace(Regex("(?i)\\s*(完结|已完结|全集|连载中).*$"), "").trim()

            // 6. 去除多余的特殊字符（但保留冒号和点，用于"复仇者: 终局之战"类片名）
            s = s.replace(Regex("[_＿【】\\[\\]《》]"), " ").replace(Regex("\\s{2,}"), " ").trim()

            return s
        }

        /**
         * 从结果列表中挑选最匹配的一条
         */
        fun pickBestMatch(rawTitle: String, result: TmdbResult?): TmdbMedia? {
            if (result == null || result.results.isEmpty()) return null
            val cleaned = cleanTitle(rawTitle).lowercase()
            var best: TmdbMedia? = null
            var bestScore = -1.0

            for (m in result.results) {
                if ("person" == m.mediaType) continue
                val mTitle = m.displayTitle.lowercase()
                val mOrig = (m.originalTitle ?: "").lowercase()

                val titleSim = stringSimilarity(cleaned, mTitle)
                val origSim = stringSimilarity(cleaned, mOrig)
                val maxSim = maxOf(titleSim, origSim)

                val score = maxSim * 0.7 + minOf(m.popularity / 200.0, 1.0) * 0.3
                if (score > bestScore) {
                    bestScore = score
                    best = m
                }
            }
            return if (bestScore > 0.2) best else null
        }

        private fun stringSimilarity(a: String?, b: String?): Double {
            if (a.isNullOrEmpty() || b.isNullOrEmpty()) return 0.0
            if (a == b) return 1.0
            val setA = bigrams(a)
            val setB = bigrams(b)
            val intersection = setA.intersect(setB)
            val union = setA.union(setB)
            if (union.isEmpty()) return 0.0
            return intersection.size.toDouble() / union.size.toDouble()
        }

        private fun bigrams(s: String): Set<String> {
            val set = mutableSetOf<String>()
            for (i in 0 until s.length - 1) {
                set.add(s.substring(i, i + 2))
            }
            if (s.length == 1) set.add(s)
            return set
        }
    }
}
