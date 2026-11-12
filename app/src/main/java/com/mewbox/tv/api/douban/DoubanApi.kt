package com.mewbox.tv.api.douban

import com.mewbox.tv.api.douban.DoubanApiException.Kind
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import timber.log.Timber
import java.util.Base64
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import javax.inject.Inject
import javax.inject.Named
import javax.inject.Singleton

/** Frodo 接口错误归一化：签名失效 / 限流 / HTTP 错误，均带可直接展示的中文文案。 */
class DoubanApiException(
    val kind: Kind,
    override val message: String,
    val httpCode: Int = 0,
) : Exception(message) {
    enum class Kind { SIGNATURE_CHANGED, RATE_LIMITED, HTTP_ERROR }
}

/** Frodo HMAC-SHA1 签名：raw = "GET&" + urlencode(path, safe="") + "&" + ts（秒）。 */
internal fun frodoSign(path: String, ts: Long): String {
    // URLEncoder 会把 "/" 编成 %2F，字母数字与 "_" 不编码，与 Python urlencode(path, safe="") 语义一致
    val encodedPath = java.net.URLEncoder.encode(path, "UTF-8")
    val raw = "GET&$encodedPath&$ts"
    val mac = Mac.getInstance("HmacSHA1").apply {
        init(SecretKeySpec(DoubanApi.SECRET.toByteArray(Charsets.UTF_8), "HmacSHA1"))
    }
    return Base64.getEncoder().encodeToString(mac.doFinal(raw.toByteArray(Charsets.UTF_8)))
}

/**
 * 豆瓣 Frodo App 后端客户端（GET /api/v2/user/{uid}/interests）。
 *
 * - 签名只签 path、绝不带 query（签整 URL 必返 code 996）；_ts 用秒且必须与签名所用 ts 完全一致。
 * - 本类不持有任何状态：分页游标、去重与节流均在 DoubanRepository。
 * - 凭据为豆瓣 Android App 公开常量（逆向通道，无 SLA），失效时归一化为「接口已变更」提示。
 */
@Singleton
class DoubanApi @Inject constructor(
    @param:Named("douban") private val client: OkHttpClient,
) {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    suspend fun interests(uid: String, start: Int, count: Int = PAGE_SIZE): DoubanInterestsResponse =
        withContext(Dispatchers.IO) {
            val path = "/api/v2/user/$uid/interests"
            val ts = System.currentTimeMillis() / 1000
            // query 用 HttpUrl.Builder 拼装，自动处理 _sig 里的 "+" "/" "="
            val url = HttpUrl.Builder()
                .scheme("https")
                .host(HOST)
                .encodedPath(path)
                .addQueryParameter("type", "movie")
                .addQueryParameter("status", "mark")
                .addQueryParameter("start", start.toString())
                .addQueryParameter("count", count.toString())
                .addQueryParameter("apiKey", API_KEY)
                .addQueryParameter("_ts", ts.toString())
                .addQueryParameter("_sig", frodoSign(path, ts))
                .addQueryParameter("os_rom", "android")
                .build()
            val request = Request.Builder().url(url).build()

            client.newCall(request).execute().use { response ->
                val body = response.body?.string().orEmpty()
                if (!response.isSuccessful) {
                    throw mapError(response.code, body)
                }
                runCatching { json.decodeFromString<DoubanInterestsResponse>(body) }
                    .getOrElse { e ->
                        Timber.e(e, "豆瓣 interests 响应解析失败")
                        throw DoubanApiException(Kind.HTTP_ERROR, "豆瓣响应解析失败", response.code)
                    }
            }
        }

    /** 错误码归一化：997/996 → 签名问题（不自动重试）；103/403/need_login → 限流（可退避重试）。 */
    private fun mapError(httpCode: Int, body: String): DoubanApiException {
        val code = runCatching {
            json.parseToJsonElement(body).jsonObject["code"]?.jsonPrimitive?.content
        }.getOrNull()
        val exception = when {
            code == "997" || code == "996" || code == "invalid_request_997" ->
                DoubanApiException(Kind.SIGNATURE_CHANGED, "豆瓣接口已变更，请稍后重试", httpCode)

            code == "103" || body.contains("need_login") || httpCode == 403 ->
                DoubanApiException(Kind.RATE_LIMITED, "豆瓣请求过于频繁，请稍后再试", httpCode)

            else -> DoubanApiException(Kind.HTTP_ERROR, "豆瓣请求失败（HTTP $httpCode）", httpCode)
        }
        Timber.w("豆瓣 interests 请求失败 code=%s http=%s", code, httpCode)
        return exception
    }

    companion object {
        const val PAGE_SIZE = 50
        const val FRODO_UA = (
            "api-client/1 com.douban.frodo/7.22.0.beta9(231) Android/23 " +
                "product/Mate40 vendor/HUAWEI model/Mate40 brand/HUAWEI " +
                "rom/android network/wifi platform/AndroidPad"
            )
        const val DOUBAN_REFERER = "https://movie.douban.com/"
        /** 豆瓣图片 CDN 专用 UA：其防盗链同时校验 UA，okhttp UA 会被 403。 */
        const val BROWSER_UA = "Mozilla/5.0 (Linux; Android 9) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0 Mobile Safari/537.36"
        private const val HOST = "frodo.douban.com"
        private const val API_KEY = "0dad551ec0f84ed02907ff5c42e8ec70"
        internal const val SECRET = "bf7dddc7c9cfe6f7"
    }
}
