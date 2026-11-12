package com.mewbox.tv.api.net

import android.content.Context
import com.mewbox.tv.api.parser.ResultParser
import com.mewbox.tv.api.parser.optArr
import com.mewbox.tv.api.parser.optBoolean
import com.mewbox.tv.api.parser.optInt
import com.mewbox.tv.api.parser.optLong
import com.mewbox.tv.api.parser.optObj
import com.mewbox.tv.api.parser.optString
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.Headers
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import timber.log.Timber
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.URLDecoder
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.security.SecureRandom
import java.util. Base64
import java.util.Locale
import java.util.Random
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.regex.Pattern
import java.util.zip.GZIPInputStream
import java.util.zip.Inflater
import java.util.zip.InflaterInputStream
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 网盘分享链接有效性批量检测服务（对齐 MewPlayer DriveCheckService）。
 * 支持夸克 (quark)、UC (uc)、阿里云盘 (aliyun)、百度网盘 (baidu)、天翼云盘 (tianyi)、
 * 123云盘 (123)、迅雷云盘 (xunlei)、115网盘 (115)、移动云盘 (mobile)。
 */
@Singleton
class DriveCheckService @Inject constructor(
    @param:ApplicationContext private val context: Context,
) {
    data class DriveCheckItem(
        val type: String,
        val url: String,
        val password: String = "",
    ) {
        val diskType: String
            get() = when (val normalized = type.trim().lowercase(Locale.ROOT)) {
                "ali", "alipan", "aliyundrive" -> "aliyun"
                "189", "ty" -> "tianyi"
                "123pan" -> "123"
                "139", "yidong", "caiyun" -> "mobile"
                "xl" -> "xunlei"
                else -> normalized.ifEmpty { inferDiskType(url) }
            }
    }

    data class DriveCheckResult(
        val type: String,
        val url: String,
        val normalizedUrl: String,
        val state: String,
        val cacheHit: Boolean,
        val checkedAt: Long,
        val expiresAt: Long,
        val summary: String,
    ) {
        fun asCacheHit(): DriveCheckResult = copy(cacheHit = true)

        fun toJson(): JsonObject = buildJsonObject {
            put("type", type)
            put("url", url)
            put("normalizedUrl", normalizedUrl)
            put("state", state)
            put("cacheHit", cacheHit)
            put("checkedAt", checkedAt)
            put("expiresAt", expiresAt)
            put("summary", summary)
        }
    }

    private data class HttpResult(val code: Int, val body: String)

    private val memory = ConcurrentHashMap<String, DriveCheckResult>()
    private val client: OkHttpClient = OkHttpClient.Builder()
        .followRedirects(true)
        .followSslRedirects(true)
        .connectTimeout(12, TimeUnit.SECONDS)
        .readTimeout(12, TimeUnit.SECONDS)
        .writeTimeout(12, TimeUnit.SECONDS)
        .build()

    init {
        loadCache()
    }

    suspend fun checkFromJson(payload: JsonObject): JsonObject = withContext(Dispatchers.IO) {
        val rawItems = payload.optArr("items") ?: JsonArray(emptyList())
        val items = rawItems.mapNotNull { elem ->
            val obj = elem as? JsonObject ?: return@mapNotNull null
            DriveCheckItem(
                type = obj.optString("type", obj.optString("diskType", "")),
                url = obj.optString("url", ""),
                password = obj.optString("password", obj.optString("pwd", obj.optString("passcode", ""))),
            )
        }
        val results = check(items)
        buildJsonObject {
            put(
                "results",
                buildJsonArray {
                    results.forEach { add(it.toJson()) }
                },
            )
        }
    }

    suspend fun check(items: List<DriveCheckItem>): List<DriveCheckResult> = coroutineScope {
        if (items.isEmpty()) return@coroutineScope emptyList()
        val semaphore = Semaphore(BATCH_SIZE)
        items.map { item ->
            async(Dispatchers.IO) {
                semaphore.withPermit {
                    checkOneSafely(item)
                }
            }
        }.awaitAll()
    }

    private fun checkOneSafely(item: DriveCheckItem): DriveCheckResult {
        return try {
            checkOne(item)
        } catch (e: Throwable) {
            makeResult(item.diskType, item.url, "", STATE_UNCERTAIN, false, coalesce(e.message, "检测失败"))
        }
    }

    private fun checkOne(item: DriveCheckItem): DriveCheckResult {
        val diskType = item.diskType
        val normalized = normalizeShareLink(diskType, item.url, item.password)
        if (diskType.isEmpty() || item.url.isBlank()) {
            return makeResult(diskType, item.url, normalized, STATE_UNCERTAIN, false, "type 和 url 不能为空")
        }
        if (normalized.isEmpty()) {
            return makeResult(diskType, item.url, "", STATE_UNCERTAIN, false, "链接格式无效")
        }

        val key = cacheKey(diskType, normalized)
        getCached(key)?.let { return it.asCacheHit() }

        var cacheable = true
        val checked = try {
            runCheck(item, diskType, normalized)
        } catch (e: Throwable) {
            cacheable = false
            makeResult(diskType, item.url, normalized, STATE_UNCERTAIN, false, coalesce(e.message, "检测失败"))
        }

        if (cacheable) {
            memory[key] = checked
            saveCache()
        }
        return checked
    }

    private fun runCheck(item: DriveCheckItem, diskType: String, normalized: String): DriveCheckResult {
        return when (diskType) {
            "aliyun" -> checkAliyun(item, diskType, normalized)
            "quark" -> checkQuark(item, diskType, normalized)
            "uc" -> checkUC(item, diskType, normalized)
            "baidu" -> checkBaidu(item, diskType, normalized)
            "tianyi" -> checkTianyi(item, diskType, normalized)
            "123" -> check123(item, diskType, normalized)
            "xunlei" -> checkXunlei(item, diskType, normalized)
            "115" -> check115(item, diskType, normalized)
            "mobile" -> checkMobile(item, diskType, normalized)
            else -> makeResult(diskType, item.url, normalized, STATE_UNSUPPORTED, false, "当前平台暂不支持检测")
        }
    }

    private fun checkAliyun(item: DriveCheckItem, diskType: String, normalized: String): DriveCheckResult {
        val shareId = extractAliyunShareId(normalized)
        if (shareId.isEmpty()) return makeResult(diskType, item.url, normalized, STATE_UNCERTAIN, false, "无法解析分享地址")

        val body = buildJsonObject { put("share_id", shareId) }.toString()
        val http = request(
            method = "POST",
            url = "https://api.aliyundrive.com/adrive/v3/share_link/get_share_by_anonymous?share_id=${enc(shareId)}",
            body = body,
            headers = mapOf(
                "Content-Type" to "application/json",
                "Origin" to "https://www.alipan.com",
                "Referer" to "https://www.alipan.com/",
                "x-canary" to "client=web,app=share,version=v2.3.1",
            ),
        )
        val obj = parseObj(http.body)
        val code = obj.optString("code", "").trim()
        val message = coalesce(obj.optString("message", ""), code)
        if (code.isNotEmpty()) {
            val lower = code.lowercase(Locale.ROOT)
            if (lower.contains("sharelink") || containsAny(lower, "notfound", "cancelled", "canceled", "forbidden", "expired")) {
                return makeResult(diskType, item.url, normalized, STATE_BAD, false, message)
            }
            return makeResult(diskType, item.url, normalized, STATE_UNCERTAIN, false, message)
        }
        val status = obj.optString("share_status", "").lowercase(Locale.ROOT)
        if (status.isNotEmpty() && status != "enabled" && status != "normal" &&
            containsAny(status, "forbidden", "cancel", "expired", "illegal", "invalid", "disabled")
        ) {
            return makeResult(diskType, item.url, normalized, STATE_BAD, false, coalesce(message, "链接失效"))
        }
        val hasName = obj.optString("share_name", "").isNotBlank() || obj.optString("share_title", "").isNotBlank()
        val fileCount = obj.optInt("file_count", -1)
        if (http.code == 200 && (hasName || fileCount > 0)) {
            return makeResult(diskType, item.url, normalized, STATE_OK, false, "链接有效")
        }
        if (fileCount == 0 && !hasName) {
            return makeResult(diskType, item.url, normalized, STATE_BAD, false, "分享内容为空")
        }
        if (http.code != 200) {
            return makeResult(diskType, item.url, normalized, STATE_UNCERTAIN, false, coalesce(message, "HTTP状态码: ${http.code}"))
        }
        return makeResult(diskType, item.url, normalized, STATE_UNCERTAIN, false, message)
    }

    private fun checkQuark(item: DriveCheckItem, diskType: String, normalized: String): DriveCheckResult {
        val (resourceId, extractedPwd) = extractQuarkShareIdAndPassword(normalized)
        val password = coalesce(extractedPwd, item.password)
        if (resourceId.isEmpty()) return makeResult(diskType, item.url, normalized, STATE_UNCERTAIN, false, "无法解析分享地址")

        val body = buildJsonObject {
            put("pwd_id", resourceId)
            put("passcode", password)
            put("support_visit_limit_private_share", true)
        }.toString()
        val tokenHttp = request(
            method = "POST",
            url = "https://drive-h.quark.cn/1/clouddrive/share/sharepage/token",
            body = body,
            headers = mapOf(
                "Content-Type" to "application/json",
                "Origin" to "https://pan.quark.cn",
                "Referer" to "https://pan.quark.cn/",
            ),
        )
        val tokenObj = parseObj(tokenHttp.body)
        val code = tokenObj.optInt("code", 0)
        val message = tokenObj.optString("message", "").trim()
        if (code == 41008) return makeResult(diskType, item.url, normalized, STATE_LOCKED, false, "需要提取码")
        if (code == 41004 || code == 41010 || code == 41011) return makeResult(diskType, item.url, normalized, STATE_BAD, false, "链接失效")
        if (code != 0) {
            val lower = message.lowercase(Locale.ROOT)
            if (containsAny(lower, "不存在", "失效", "违规", "过期", "取消")) return makeResult(diskType, item.url, normalized, STATE_BAD, false, message)
            if (containsAny(lower, "提取码", "密码")) return makeResult(diskType, item.url, normalized, STATE_LOCKED, false, message)
            return makeResult(diskType, item.url, normalized, STATE_UNCERTAIN, false, message)
        }

        val stoken = tokenObj.optObj("data")?.optString("stoken", "")?.trim().orEmpty()
        if (stoken.isEmpty()) return makeResult(diskType, item.url, normalized, STATE_UNCERTAIN, false, "访问令牌缺失")

        val detailUrl = "https://drive-pc.quark.cn/1/clouddrive/share/sharepage/detail?pwd_id=${enc(resourceId)}&stoken=${enc(stoken)}&ver=2&pr=ucpro"
        val detailHttp = request(
            method = "GET",
            url = detailUrl,
            body = null,
            headers = mapOf(
                "Accept" to "application/json, text/plain, */*",
                "Origin" to "https://pan.quark.cn",
                "Referer" to "https://pan.quark.cn/",
                "Cache-Control" to "no-cache",
            ),
        )
        val detail = parseObj(detailHttp.body)
        val detailCode = detail.optInt("code", 0)
        val detailMessage = detail.optString("message", "").trim()
        if (detailCode != 0) {
            val lower = detailMessage.lowercase(Locale.ROOT)
            if (containsAny(lower, "提取码", "密码", "passcode")) return makeResult(diskType, item.url, normalized, STATE_LOCKED, false, coalesce(detailMessage, "需要提取码"))
            if (containsAny(lower, "不存在", "失效", "违规", "过期", "取消")) return makeResult(diskType, item.url, normalized, STATE_BAD, false, coalesce(detailMessage, "链接失效"))
            return makeResult(diskType, item.url, normalized, STATE_UNCERTAIN, false, coalesce(detailMessage, "无法确认链接状态"))
        }

        val detailData = detail.optObj("data") ?: JsonObject(emptyMap())
        val share = detailData.optObj("share") ?: JsonObject(emptyMap())
        val size = detailData.optArr("list")?.size ?: 0
        val shareStatus = share.optInt("status", 0)
        val partialViolation = share.optBoolean("partial_violation", false)
        val expired = detailData.optBoolean("is_expire", false)
        if (size == 0) {
            if (shareStatus > 1 && partialViolation) return makeResult(diskType, item.url, normalized, STATE_BAD, false, "分享链接部分违规已失效")
            if (shareStatus > 1 || expired) return makeResult(diskType, item.url, normalized, STATE_BAD, false, "分享链接已失效")
            return makeResult(diskType, item.url, normalized, STATE_BAD, false, "分享链接无效：文件列表为空")
        }
        if (shareStatus == 3 && partialViolation) return makeResult(diskType, item.url, normalized, STATE_BAD, false, "分享链接因违规已失效")
        if (shareStatus > 1 && shareStatus != 3) return makeResult(diskType, item.url, normalized, STATE_BAD, false, "分享链接已失效")
        return makeResult(diskType, item.url, normalized, STATE_OK, false, if (partialViolation) "链接有效但部分文件违规" else "链接有效")
    }

    private fun checkUC(item: DriveCheckItem, diskType: String, normalized: String): DriveCheckResult {
        val http = request("GET", normalized, null, mapOf("User-Agent" to mobileUa()))
        if (http.code == 404) return makeResult(diskType, item.url, normalized, STATE_BAD, false, "链接失效")
        val text = http.body.lowercase(Locale.ROOT)
        if (containsAny(text, "失效", "不存在", "违规", "删除", "已过期", "被取消")) return makeResult(diskType, item.url, normalized, STATE_BAD, false, "链接失效")
        if (containsAny(text, "提取码", "访问码", "请输入密码")) return makeResult(diskType, item.url, normalized, STATE_LOCKED, false, "需要提取码")
        if (containsAny(text, "文件", "分享", "drive.uc.cn")) return makeResult(diskType, item.url, normalized, STATE_OK, false, "链接有效")
        return makeResult(diskType, item.url, normalized, STATE_UNCERTAIN, false, "无法确认链接状态")
    }

    private fun checkBaidu(item: DriveCheckItem, diskType: String, normalized: String): DriveCheckResult {
        val (shareId, shortUrl, extractedPwd) = extractBaiduShareInfo(normalized)
        val password = coalesce(extractedPwd, item.password)
        if (shareId.isEmpty() || shortUrl.isEmpty()) return makeResult(diskType, item.url, normalized, STATE_UNCERTAIN, false, "无法解析分享地址")

        var bdclnd = ""
        if (password.isNotEmpty()) {
            val verifyUrl = "https://pan.baidu.com/share/verify?surl=${enc(shortUrl)}&pwd=${enc(password)}"
            val verify = request(
                method = "POST",
                url = verifyUrl,
                body = "pwd=${enc(password)}&vcode=&vcode_str=",
                headers = mapOf(
                    "Referer" to normalized,
                    "Content-Type" to "application/x-www-form-urlencoded",
                ),
            )
            val obj = parseObj(verify.body)
            val errno = obj.optInt("errno", 0)
            if (errno == 0) bdclnd = obj.optString("randsk", "").trim()
            else if (errno == -9 || errno == -12) return makeResult(diskType, item.url, normalized, STATE_LOCKED, false, "提取码错误或缺失")
            else return makeResult(diskType, item.url, normalized, STATE_UNCERTAIN, false, obj.optString("errmsg", ""))
        }

        val headers = mutableMapOf(
            "Accept" to "application/json, text/plain, */*",
            "Referer" to normalized,
            "User-Agent" to pcUa(),
        )
        if (bdclnd.isNotEmpty()) headers["Cookie"] = "BDCLND=$bdclnd"
        val listUrl = "https://pan.baidu.com/share/list?web=1&page=1&num=20&order=time&desc=1&showempty=0&shorturl=${enc(shortUrl)}&root=1&clienttype=0"
        val listHttp = request("GET", listUrl, null, headers)
        val obj = parseObj(listHttp.body)
        val errno = obj.optInt("errno", 0)
        if (errno == 0) {
            val size = obj.optArr("list")?.size ?: 0
            return makeResult(
                diskType,
                item.url,
                normalized,
                if (size > 0) STATE_OK else STATE_BAD,
                false,
                if (size > 0) "链接有效" else "链接失效",
            )
        }
        if (errno == -9 || errno == -12) return makeResult(diskType, item.url, normalized, STATE_LOCKED, false, "需要提取码")
        if (errno == -7 || errno == 105 || errno == 115 || errno == 117 || errno == 145) {
            return makeResult(diskType, item.url, normalized, STATE_BAD, false, "链接失效")
        }
        return makeResult(diskType, item.url, normalized, STATE_UNCERTAIN, false, obj.optString("errmsg", ""))
    }

    private fun checkTianyi(item: DriveCheckItem, diskType: String, normalized: String): DriveCheckResult {
        val (rawShareCode, password, referer) = extractTianyiShareInfo(normalized, item.password)
        if (rawShareCode.isEmpty()) return makeResult(diskType, item.url, normalized, STATE_UNCERTAIN, false, "无法解析分享地址")
        val shareCode = if (password.isNotEmpty()) "$rawShareCode（访问码：$password）" else rawShareCode
        val api = "https://cloud.189.cn/api/open/share/getShareInfoByCodeV2.action?noCache=${Random().nextDouble()}&shareCode=${enc(shareCode)}"
        val http = request("GET", api, null, mapOf("Referer" to referer, "sign-type" to "1"))
        val body = http.body
        val lower = body.lowercase(Locale.ROOT)
        if (body.contains("<shareId>") || body.contains("<fileName>") || body.contains("<needAccessCode>1</needAccessCode>")) {
            return makeResult(diskType, item.url, normalized, STATE_OK, false, "链接有效")
        }
        if (containsAny(lower, "erroraccesscode", "needaccesscode", "访问码", "提取码", "密码")) {
            return makeResult(diskType, item.url, normalized, STATE_LOCKED, false, "需要访问码")
        }
        val knownCode = scanTianyiKnownErrorCode(body)
        if (knownCode.isNotEmpty()) return makeResult(diskType, item.url, normalized, STATE_BAD, false, mapTianyiErrorMessage(knownCode, ""))
        if (containsAny(lower, "shareinfonotfound", "sharenotfound", "filenotfound", "shareexpirederror", "shareauditnotpass", "foldernotfound", "不存在", "失效", "取消", "过期")) {
            return makeResult(diskType, item.url, normalized, STATE_BAD, false, "链接失效")
        }
        val obj = parseObj(body)
        if (obj.optInt("shareId", 0) > 0 || obj.optString("fileName", "").isNotBlank() || obj.optInt("needAccessCode", 0) == 1) {
            return makeResult(diskType, item.url, normalized, STATE_OK, false, "链接有效")
        }
        val errorCode = coalesce(obj.optString("error_code", ""), scanTianyiKnownErrorCode(obj.optString("res_message", "")))
        if (errorCode.isNotEmpty()) {
            return makeResult(diskType, item.url, normalized, STATE_BAD, false, mapTianyiErrorMessage(errorCode, obj.optString("res_message", "")))
        }
        return makeResult(diskType, item.url, normalized, STATE_UNCERTAIN, false, "无法确认链接状态")
    }

    private fun check123(item: DriveCheckItem, diskType: String, normalized: String): DriveCheckResult {
        val shareKey = extract123ShareKey(normalized)
        if (shareKey.isEmpty()) return makeResult(diskType, item.url, normalized, STATE_UNCERTAIN, false, "无法解析分享地址")
        val http = request("GET", "https://www.123pan.com/api/share/info?shareKey=${enc(shareKey)}", null, null)
        if (http.code == 403) return makeResult(diskType, item.url, normalized, STATE_OK, false, "链接有效")
        val obj = parseObj(http.body)
        val code = obj.optInt("code", -1)
        if (code == 0) return makeResult(diskType, item.url, normalized, STATE_OK, false, "链接有效")
        if (obj.optObj("data")?.optBoolean("HasPwd", false) == true) {
            return makeResult(diskType, item.url, normalized, STATE_LOCKED, false, "需要提取码")
        }
        return makeResult(diskType, item.url, normalized, STATE_BAD, false, coalesce(obj.optString("message", ""), "链接失效"))
    }

    private fun checkXunlei(item: DriveCheckItem, diskType: String, normalized: String): DriveCheckResult {
        val (shareId, extractedPwd) = extractXunleiShareInfo(normalized)
        val password = coalesce(extractedPwd, item.password)
        if (shareId.isEmpty()) return makeResult(diskType, item.url, normalized, STATE_UNCERTAIN, false, "无法解析分享地址")
        val captcha = fetchXunleiCaptchaToken()
        val api = "https://api-pan.xunlei.com/drive/v1/share?share_id=${enc(shareId)}&pass_code=${enc(password)}&limit=100&pass_code_token=&page_token=&thumbnail_size=SIZE_SMALL"
        val headers = mutableMapOf(
            "Accept" to "*/*",
            "Content-Type" to "application/json",
            "Origin" to "https://pan.xunlei.com",
            "Referer" to "https://pan.xunlei.com/",
            "User-Agent" to pcUa(),
            "Accept-Encoding" to "gzip, deflate",
            "x-client-id" to "ZUBzD9J_XPXfn7f7",
            "x-device-id" to "5505bd0cab8c9469b98e5891d9fb3e0d",
        )
        if (captcha.isNotEmpty()) headers["x-captcha-token"] = captcha
        val http = request("GET", api, null, headers)
        if (http.code == 404 || http.code == 403) return makeResult(diskType, item.url, normalized, STATE_BAD, false, "链接失效")
        val obj = parseObj(http.body)
        val status = obj.optString("share_status", "").trim()
        val statusText = obj.optString("share_status_text", "").trim()
        val error = obj.optString("error", "").trim()
        val errorMsg = obj.optString("error_description", "").trim()
        if (status == "OK" || obj.optString("share_id", "").isNotBlank() || obj.optString("share_name", "").isNotBlank() || obj.optInt("file_count", 0) > 0) {
            return makeResult(diskType, item.url, normalized, STATE_OK, false, "链接有效")
        }
        if (containsAny(error.lowercase(Locale.ROOT), "pass_code") ||
            containsAny(errorMsg.lowercase(Locale.ROOT), "pass_code", "提取码", "密码") ||
            containsAny(statusText.lowercase(Locale.ROOT), "pass_code", "提取码", "密码")
        ) {
            return makeResult(diskType, item.url, normalized, STATE_LOCKED, false, coalesce(errorMsg, statusText, "需要提取码"))
        }
        val message = coalesce(statusText, errorMsg, error)
        if (status.isNotEmpty() && status != "OK") return makeResult(diskType, item.url, normalized, STATE_BAD, false, coalesce(message, "分享状态: $status"))
        if (message.isNotEmpty() && containsAny(message.lowercase(Locale.ROOT), "参数错误", "share_status", "不存在", "失效", "过期", "not found")) {
            return makeResult(diskType, item.url, normalized, STATE_BAD, false, message)
        }
        return makeResult(diskType, item.url, normalized, STATE_UNCERTAIN, false, coalesce(message, "无法确认链接状态"))
    }

    private fun check115(item: DriveCheckItem, diskType: String, normalized: String): DriveCheckResult {
        val (shareCode, password) = extract115ShareInfo(normalized, item.password)
        if (shareCode.isEmpty()) return makeResult(diskType, item.url, normalized, STATE_UNCERTAIN, false, "无法解析分享地址")
        if (password.isEmpty()) return makeResult(diskType, item.url, normalized, STATE_LOCKED, false, "115 需要提取码")
        val api = "https://115cdn.com/webapi/share/snap?share_code=${enc(shareCode)}&offset=0&limit=20&receive_code=${enc(password)}&cid="
        val http = request(
            method = "GET",
            url = api,
            body = null,
            headers = mapOf(
                "Referer" to "https://115cdn.com/s/$shareCode?password=$password&",
                "X-Requested-With" to "XMLHttpRequest",
            ),
        )
        val obj = parseObj(http.body)
        val state = obj.optBoolean("state", false)
        val errno = obj.optInt("errno", -1)
        val data = obj.optObj("data") ?: JsonObject(emptyMap())
        val shareInfo = data.optObj("shareinfo") ?: JsonObject(emptyMap())
        if (state && errno == 0) {
            val shareState = classify115ShareState(data)
            val summary = if (shareState == STATE_OK) "链接有效" else coalesce(shareInfo.optString("forbid_reason", ""), "链接状态异常")
            return makeResult(diskType, item.url, normalized, shareState, false, summary)
        }
        val error = obj.optString("error", "").trim()
        if (containsAny(error.lowercase(Locale.ROOT), "密码", "提取码", "receive_code")) {
            return makeResult(diskType, item.url, normalized, STATE_LOCKED, false, coalesce(error, "需要提取码"))
        }
        if (containsAny(error.lowercase(Locale.ROOT), "参数错误", "不存在", "失效", "share_code", "forbid", "forbidden", "违规", "删除", "取消")) {
            return makeResult(diskType, item.url, normalized, STATE_BAD, false, coalesce(error, "链接失效"))
        }
        return makeResult(diskType, item.url, normalized, if (error.isEmpty()) STATE_UNCERTAIN else STATE_BAD, false, coalesce(error, "无法确认链接状态"))
    }

    private fun checkMobile(item: DriveCheckItem, diskType: String, normalized: String): DriveCheckResult {
        val shareId = extractMobileShareId(normalized)
        if (shareId.isEmpty()) return makeResult(diskType, item.url, normalized, STATE_UNCERTAIN, false, "无法解析分享地址")
        val payload = buildJsonObject {
            put(
                "getOutLinkInfoReq",
                buildJsonObject {
                    put("account", "")
                    put("linkID", shareId)
                    put("passwd", item.password)
                    put("caSrt", 1)
                    put("coSrt", 1)
                    put("srtDr", 0)
                    put("bNum", 1)
                    put("pCaID", "root")
                    put("eNum", 200)
                },
            )
            put(
                "commonAccountInfo",
                buildJsonObject {
                    put("account", "")
                    put("accountType", 1)
                },
            )
        }.toString()
        val encrypted = JsonPrimitive(encryptMobile(payload)).toString()
        val http = request(
            method = "POST",
            url = "https://share-kd-njs.yun.139.com/yun-share/richlifeApp/devapp/IOutLink/getOutLinkInfoV6",
            body = encrypted,
            headers = mapOf(
                "Accept" to "application/json, text/plain, */*",
                "Content-Type" to "application/json",
                "hcy-cool-flag" to "1",
                "x-deviceinfo" to "||3|12.27.0|chrome|131.0.0.0|5c7c68368f048245e1ce47f1c0f8f2d0||windows 10|1536X695|zh-CN|||",
            ),
        )
        val obj = parseObj(decryptMobile(http.body.trim().removeSurrounding("\"")))
        val code = obj.optString("resultCode", "").trim()
        val desc = obj.optString("desc", "").trim()
        if (code == "0" && obj["data"] != null) return makeResult(diskType, item.url, normalized, STATE_OK, false, "链接有效")
        val lower = desc.lowercase(Locale.ROOT)
        if (containsAny(lower, "提取码", "密码", "访问码")) return makeResult(diskType, item.url, normalized, STATE_LOCKED, false, coalesce(desc, "需要提取码"))
        if (desc.isNotEmpty() && containsAny(lower, "失效", "不存在", "过期", "取消")) return makeResult(diskType, item.url, normalized, STATE_BAD, false, desc)
        if (desc.isNotEmpty()) return makeResult(diskType, item.url, normalized, STATE_UNCERTAIN, false, desc)
        return makeResult(diskType, item.url, normalized, if (code.isEmpty()) STATE_UNCERTAIN else STATE_BAD, false, if (code.isEmpty()) "无法确认链接状态" else "错误码: $code")
    }

    private fun fetchXunleiCaptchaToken(): String {
        return try {
            val deviceId = "5505bd0cab8c9469b98e5891d9fb3e0d"
            val clientId = "ZUBzD9J_XPXfn7f7"
            val clientVersion = "1.10.0.2633"
            val packageName = "com.xunlei.browser"
            val timestamp = System.currentTimeMillis().toString()
            val signature = "1." + xunleiSignature(clientId + clientVersion + packageName + deviceId + timestamp)
            val payload = buildJsonObject {
                put("action", "get:/drive/v1/share")
                put("captcha_token", "")
                put("client_id", clientId)
                put("device_id", deviceId)
                put(
                    "meta",
                    buildJsonObject {
                        put("timestamp", timestamp)
                        put("captcha_sign", signature)
                        put("client_version", clientVersion)
                        put("package_name", packageName)
                    },
                )
                put("redirect_uri", "xlaccsdk01://xunlei.com/callback?state=harbor")
            }.toString()
            val http = request(
                method = "POST",
                url = "https://xluser-ssl.xunlei.com/v1/shield/captcha/init",
                body = payload,
                headers = mapOf(
                    "Accept" to "application/json;charset=UTF-8",
                    "Content-Type" to "application/json",
                    "x-device-id" to deviceId,
                    "x-client-id" to clientId,
                    "x-client-version" to clientVersion,
                ),
            )
            parseObj(http.body).optString("captcha_token", "").trim()
        } catch (_: Throwable) {
            ""
        }
    }

    private fun request(method: String, url: String, body: String?, headers: Map<String, String>?): HttpResult {
        val nextHeaders = LinkedHashMap<String, String>()
        headers?.let { nextHeaders.putAll(it) }
        if (nextHeaders.keys.none { it.equals("User-Agent", ignoreCase = true) }) {
            nextHeaders["User-Agent"] = pcUa()
        }
        if (nextHeaders.keys.none { it.equals("Accept-Encoding", ignoreCase = true) }) {
            nextHeaders["Accept-Encoding"] = "gzip"
        }
        val verb = method.ifBlank { "GET" }.uppercase(Locale.ROOT)
        val reqBody = if (verb != "GET" && verb != "HEAD") {
            val ct = nextHeaders.entries.firstOrNull { it.key.equals("Content-Type", ignoreCase = true) }?.value
            val mediaType = if (ct != null && ct.startsWith("application/x-www-form-urlencoded")) FORM_MEDIA else JSON_MEDIA
            (body ?: "").toRequestBody(mediaType)
        } else null

        val headerBuilder = Headers.Builder()
        nextHeaders.forEach { (k, v) -> headerBuilder.set(k, v) }

        val req = Request.Builder()
            .url(url)
            .headers(headerBuilder.build())
            .method(verb, reqBody)
            .build()

        client.newCall(req).execute().use { response ->
            val raw = response.body.byteStream().use { readAll(it) }
            val decoded = decode(response.header("Content-Encoding"), raw)
            return HttpResult(response.code, String(decoded, StandardCharsets.UTF_8))
        }
    }

    private fun getCached(key: String): DriveCheckResult? {
        val result = memory[key] ?: return null
        if (System.currentTimeMillis() <= result.expiresAt) return result
        memory.remove(key)
        return null
    }

    private fun loadCache() {
        runCatching {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            val raw = prefs.getString(CACHE_KEY, null) ?: return
            val obj = parseObj(raw)
            val now = System.currentTimeMillis()
            for ((key, value) in obj) {
                val item = value as? JsonObject ?: continue
                val expiresAt = item.optLong("expiresAt", 0L)
                if (expiresAt > now) {
                    memory[key] = DriveCheckResult(
                        type = item.optString("type", ""),
                        url = item.optString("url", ""),
                        normalizedUrl = item.optString("normalizedUrl", ""),
                        state = item.optString("state", STATE_UNCERTAIN),
                        cacheHit = true,
                        checkedAt = item.optLong("checkedAt", now),
                        expiresAt = expiresAt,
                        summary = item.optString("summary", ""),
                    )
                }
            }
        }
    }

    @Synchronized
    private fun saveCache() {
        runCatching {
            val now = System.currentTimeMillis()
            val sorted = memory.entries
                .filter { it.value.expiresAt > now }
                .sortedByDescending { it.value.checkedAt }
                .take(CACHE_LIMIT)
            val serialized = buildJsonObject {
                sorted.forEach { (k, v) -> put(k, v.toJson()) }
            }.toString()
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit()
                .putString(CACHE_KEY, serialized)
                .apply()
        }.onFailure {
            Timber.w(it, "保存网盘检测缓存失败")
        }
    }

    private fun makeResult(
        diskType: String,
        url: String,
        normalized: String,
        state: String,
        cacheHit: Boolean,
        summary: String,
    ): DriveCheckResult {
        val now = System.currentTimeMillis()
        return DriveCheckResult(
            type = diskType,
            url = url,
            normalizedUrl = normalized,
            state = state,
            cacheHit = cacheHit,
            checkedAt = now,
            expiresAt = now + ttl(state),
            summary = summary,
        )
    }

    private fun ttl(state: String): Long = when (state) {
        STATE_OK -> TimeUnit.HOURS.toMillis(1)
        STATE_UNSUPPORTED -> TimeUnit.HOURS.toMillis(24)
        STATE_BAD -> TimeUnit.HOURS.toMillis(6)
        STATE_LOCKED -> TimeUnit.HOURS.toMillis(12)
        else -> TimeUnit.MINUTES.toMillis(30)
    }

    companion object {
        const val STATE_OK = "ok"
        const val STATE_BAD = "bad"
        const val STATE_LOCKED = "locked"
        const val STATE_UNSUPPORTED = "unsupported"
        const val STATE_UNCERTAIN = "uncertain"

        private const val PREFS_NAME = "fongmi_drive_check"
        private const val CACHE_KEY = "drive_check_cache"
        private const val CACHE_LIMIT = 300
        private const val BATCH_SIZE = 10

        private val JSON_MEDIA = "application/json; charset=utf-8".toMediaType()
        private val FORM_MEDIA = "application/x-www-form-urlencoded; charset=utf-8".toMediaType()
        private val MOBILE_KEY = "PVGDwmcvfs1uV3d1".toByteArray(StandardCharsets.UTF_8)

        fun isPanShareUrl(rawUrl: String): Boolean {
            val u = rawUrl.trim()
            if (u.startsWith("push://", ignoreCase = true) ||
                u.startsWith("magnet:", ignoreCase = true) ||
                u.startsWith("ed2k://", ignoreCase = true)
            ) {
                return true
            }
            return inferDiskType(u).isNotEmpty()
        }

        fun inferDiskType(rawUrl: String): String {
            val lower = rawUrl.trim().lowercase(Locale.ROOT)
            return when {
                lower.contains("pan.quark.cn") || lower.contains("quark.cn/s/") -> "quark"
                lower.contains("drive.uc.cn") || lower.contains("uc.cn/s/") -> "uc"
                lower.contains("alipan.com") || lower.contains("aliyundrive.com") -> "aliyun"
                lower.contains("pan.baidu.com") -> "baidu"
                lower.contains("cloud.189.cn") -> "tianyi"
                lower.contains("123pan.") || lower.contains("123684.com") || lower.contains("123685.com") ||
                    lower.contains("123912.com") || lower.contains("123592.com") || lower.contains("123865.com") -> "123"
                lower.contains("pan.xunlei.com") -> "xunlei"
                lower.contains("115.com") || lower.contains("115cdn.com") || lower.contains("anxia.com") -> "115"
                lower.contains("yun.139.com") || lower.contains("caiyun.139.com") || lower.contains("caiyun.feixin.10086.cn") -> "mobile"
                else -> ""
            }
        }

        private fun cacheKey(diskType: String, normalized: String): String =
            "${if (diskType == "115") "115:v2" else diskType}|$normalized"

        private fun normalizeShareLink(diskType: String, rawUrl: String, password: String): String {
            val base = rawUrl.trim()
            if (base.isEmpty()) return ""
            val noFragment = base.substringBefore('#')
            val queryIndex = noFragment.indexOf('?')
            val path = if (queryIndex >= 0) noFragment.substring(0, queryIndex) else noFragment
            var query = if (queryIndex >= 0) noFragment.substring(queryIndex + 1) else ""
            if (password.isNotEmpty() && (diskType == "baidu" || diskType == "quark" || diskType == "uc") &&
                !query.lowercase(Locale.ROOT).contains("pwd=")
            ) {
                query = if (query.isEmpty()) "pwd=${enc(password)}" else "$query&pwd=${enc(password)}"
            }
            return if (query.isEmpty()) path else "$path?$query"
        }

        private fun classify115ShareState(data: JsonObject): String {
            val shareInfo = data.optObj("shareinfo") ?: JsonObject(emptyMap())
            val shareState = data.optInt("share_state", shareInfo.optInt("share_state", 0))
            val reason = shareInfo.optString("forbid_reason", "").trim()
            if (shareState == 7) return STATE_BAD
            if (reason.isNotEmpty()) {
                return if (containsAny(reason.lowercase(Locale.ROOT), "密码", "提取码")) STATE_LOCKED else STATE_BAD
            }
            if (shareState == 1) return STATE_OK
            val size = data.optArr("list")?.size ?: 0
            if (size > 0 || data.optInt("count", 0) > 0 ||
                shareInfo.optString("snap_id", "").isNotBlank() ||
                shareInfo.optString("share_title", "").isNotBlank()
            ) {
                return STATE_OK
            }
            return STATE_BAD
        }

        private fun parseObj(text: String): JsonObject =
            ResultParser.parseJson(text) as? JsonObject ?: JsonObject(emptyMap())

        private fun decode(encoding: String?, bytes: ByteArray): ByteArray {
            if (bytes.isEmpty()) return bytes
            val hasGzip = encoding?.split(",")?.any { it.trim().equals("gzip", ignoreCase = true) } == true
            val isGzipMagic = bytes.size > 2 && (bytes[0].toInt() and 0xff) == 0x1f && (bytes[1].toInt() and 0xff) == 0x8b
            if (hasGzip || isGzipMagic) {
                return GZIPInputStream(ByteArrayInputStream(bytes)).use { readAll(it) }
            }
            val hasDeflate = encoding?.split(",")?.any { it.trim().equals("deflate", ignoreCase = true) } == true
            if (hasDeflate) {
                return runCatching {
                    InflaterInputStream(ByteArrayInputStream(bytes), Inflater(true)).use { readAll(it) }
                }.getOrElse {
                    InflaterInputStream(ByteArrayInputStream(bytes), Inflater(false)).use { readAll(it) }
                }
            }
            return bytes
        }

        private fun readAll(input: InputStream): ByteArray {
            val out = ByteArrayOutputStream()
            val buf = ByteArray(8192)
            var n: Int
            while (input.read(buf).also { n = it } != -1) {
                out.write(buf, 0, n)
            }
            return out.toByteArray()
        }

        private fun enc(value: String?): String =
            URLEncoder.encode(value.orEmpty(), StandardCharsets.UTF_8.name())

        private fun decodeParam(value: String?): String =
            runCatching { URLDecoder.decode(value.orEmpty(), StandardCharsets.UTF_8.name()) }.getOrDefault(value.orEmpty())

        private fun coalesce(vararg values: String?): String {
            for (v in values) {
                val trimmed = v?.trim().orEmpty()
                if (trimmed.isNotEmpty()) return trimmed
            }
            return ""
        }

        private fun containsAny(content: String?, vararg keywords: String): Boolean {
            if (content.isNullOrEmpty()) return false
            return keywords.any { content.contains(it.lowercase(Locale.ROOT)) }
        }

        private fun pcUa(): String =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0 Safari/537.36"

        private fun mobileUa(): String =
            "Mozilla/5.0 (Linux; Android 10; Mobile) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0 Safari/537.36"

        private fun extractAliyunShareId(rawUrl: String): String =
            path(rawUrl).split("/").lastOrNull().orEmpty()

        private fun extractQuarkShareIdAndPassword(rawUrl: String): Pair<String, String> {
            val matcher = Pattern.compile("/s/([A-Za-z0-9]+)").matcher(rawUrl)
            val id = if (matcher.find()) matcher.group(1).orEmpty() else ""
            return id to query(rawUrl, "pwd")
        }

        private fun extractBaiduShareInfo(rawUrl: String): Triple<String, String, String> {
            val p = path(rawUrl)
            val pwd = query(rawUrl, "pwd")
            if (p.startsWith("/s/")) {
                val shareId = p.substring(3)
                val shortUrl = if (shareId.startsWith("1") && shareId.length > 1) shareId.substring(1) else shareId
                return Triple(shareId, shortUrl, pwd)
            }
            if (p.startsWith("/share/init")) {
                val shareId = query(rawUrl, "surl")
                val shortUrl = if (shareId.startsWith("1") && shareId.length > 1) shareId.substring(1) else shareId
                return Triple(shareId, shortUrl, pwd)
            }
            return Triple("", "", pwd)
        }

        private fun extractTianyiShareInfo(rawUrl: String, fallbackPassword: String): Triple<String, String, String> {
            var shareCode = query(rawUrl, "code")
            val p = path(rawUrl)
            if (shareCode.isEmpty() && p.startsWith("/t/")) shareCode = p.substring(3)
            if (shareCode.contains("/")) shareCode = shareCode.substringBefore('/')
            var password = fallbackPassword
            val matcher = Pattern.compile("（访问码[：:]\\s*([a-zA-Z0-9]+)）").matcher(rawUrl)
            if (matcher.find()) password = matcher.group(1).orEmpty()
            return Triple(shareCode, password, rawUrl)
        }

        private fun extract123ShareKey(rawUrl: String): String {
            val patterns = arrayOf(
                "https?://(?:www\\.)?(?:123684|123685|123912|123pan|123592|123865)\\.com/s/([a-zA-Z0-9-]+)",
                "https?://(?:www\\.)?123pan\\.cn/s/([a-zA-Z0-9-]+)",
            )
            for (pat in patterns) {
                val m = Pattern.compile(pat).matcher(rawUrl)
                if (m.find()) return m.group(1).orEmpty()
            }
            return path(rawUrl).split("/").lastOrNull().orEmpty()
        }

        private fun extractXunleiShareInfo(rawUrl: String): Pair<String, String> {
            val m = Pattern.compile("pan\\.xunlei\\.com/s/([^?/#]+)").matcher(rawUrl)
            return (if (m.find()) m.group(1).orEmpty() else "") to query(rawUrl, "pwd")
        }

        private fun extract115ShareInfo(rawUrl: String, fallbackPassword: String): Pair<String, String> {
            val shareCode = path(rawUrl).split("/").lastOrNull().orEmpty()
            val password = coalesce(query(rawUrl, "password"), fallbackPassword)
            return shareCode to password
        }

        private fun extractMobileShareId(rawUrl: String): String {
            val patterns = arrayOf(
                "https?://(?:www\\.)?yun\\.139\\.com/shareweb/#/w/i/([^&/?#]+)",
                "https?://(?:www\\.)?caiyun\\.139\\.com/w/i/([^&/?#]+)",
                "https?://(?:www\\.)?caiyun\\.139\\.com/m/i\\?([^&/?#]+)",
                "https?://caiyun\\.feixin\\.10086\\.cn/([^&/?#]+)",
            )
            for (pat in patterns) {
                val m = Pattern.compile(pat).matcher(rawUrl)
                if (m.find()) return m.group(1).orEmpty()
            }
            return ""
        }

        private fun path(rawUrl: String): String {
            return runCatching {
                val text = rawUrl.replaceFirst(Regex("^[a-zA-Z][a-zA-Z0-9+.-]*://[^/]+"), "")
                val qIdx = text.indexOf('?')
                val fIdx = text.indexOf('#')
                var end = text.length
                if (qIdx >= 0) end = minOf(end, qIdx)
                if (fIdx >= 0) end = minOf(end, fIdx)
                text.substring(0, end)
            }.getOrDefault("")
        }

        private fun query(rawUrl: String, key: String): String {
            return runCatching {
                val qIdx = rawUrl.indexOf('?')
                if (qIdx < 0) return ""
                val fIdx = rawUrl.indexOf('#', qIdx)
                val q = rawUrl.substring(qIdx + 1, if (fIdx >= 0) fIdx else rawUrl.length)
                for (part in q.split("&")) {
                    val pair = part.split("=", limit = 2)
                    if (pair.isNotEmpty() && pair[0] == key) {
                        return if (pair.size > 1) decodeParam(pair[1]) else ""
                    }
                }
                ""
            }.getOrDefault("")
        }

        private fun scanTianyiKnownErrorCode(content: String?): String {
            if (content.isNullOrEmpty()) return ""
            val codes = arrayOf(
                "ShareInfoNotFound",
                "ShareNotFound",
                "FileNotFound",
                "ShareExpiredError",
                "ShareAuditNotPass",
                "FolderNotFound",
            )
            return codes.firstOrNull { content.contains(it) }.orEmpty()
        }

        private fun mapTianyiErrorMessage(code: String?, fallback: String): String = when (code?.trim().orEmpty()) {
            "ShareInfoNotFound" -> "分享信息不存在"
            "ShareNotFound" -> "分享链接不存在"
            "FileNotFound" -> "分享文件不存在"
            "ShareExpiredError" -> "分享链接已过期"
            "ShareAuditNotPass" -> "分享因审核未通过已失效"
            "FolderNotFound" -> "分享文件夹不存在"
            else -> coalesce(fallback, code)
        }

        private fun xunleiSignature(content: String): String {
            val parts = arrayOf(
                "uWRwO7gPfdPB/0NfPtfQO+71",
                "F93x+qPluYy6jdgNpq+lwdH1ap6WOM+nfz8/V",
                "0HbpxvpXFsBK5CoTKam",
                "dQhzbhzFRcawnsZqRETT9AuPAJ+wTQso82mRv",
                "SAH98AmLZLRa6DB2u68sGhyiDh15guJpXhBzI",
                "unqfo7Z64Rie9RNHMOB",
                "7yxUdFADp3DOBvXdz0DPuKNVT35wqa5z0DEyEvf",
                "RBG",
                "ThTWPG5eC0UBqlbQ+04nZAptqGCdpv9o55A",
            )
            var value = content
            for (part in parts) value = md5(value + part)
            return value
        }

        private fun md5(text: String): String {
            val digest = MessageDigest.getInstance("MD5")
            val bytes = digest.digest(text.toByteArray(StandardCharsets.UTF_8))
            return bytes.joinToString("") { "%02x".format(it) }
        }

        private fun encryptMobile(text: String): String {
            val iv = ByteArray(16)
            SecureRandom().nextBytes(iv)
            val plain = pkcs7Pad(text.toByteArray(StandardCharsets.UTF_8))
            val cipher = Cipher.getInstance("AES/CBC/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(MOBILE_KEY, "AES"), IvParameterSpec(iv))
            val encrypted = cipher.doFinal(plain)
            val output = ByteArray(iv.size + encrypted.size)
            System.arraycopy(iv, 0, output, 0, iv.size)
            System.arraycopy(encrypted, 0, output, iv.size, encrypted.size)
            return Base64.getEncoder().encodeToString(output)
        }

        private fun decryptMobile(text: String): String {
            val encrypted = Base64.getMimeDecoder().decode(text)
            require(encrypted.size >= 16) { "响应长度异常" }
            val iv = encrypted.copyOfRange(0, 16)
            val body = encrypted.copyOfRange(16, encrypted.size)
            val cipher = Cipher.getInstance("AES/CBC/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(MOBILE_KEY, "AES"), IvParameterSpec(iv))
            val unpadded = pkcs7Unpad(cipher.doFinal(body))
            return String(unpadded, StandardCharsets.UTF_8)
        }

        private fun pkcs7Pad(input: ByteArray): ByteArray {
            val padding = 16 - (input.size % 16)
            val out = ByteArray(input.size + padding)
            System.arraycopy(input, 0, out, 0, input.size)
            for (i in input.size until out.size) out[i] = padding.toByte()
            return out
        }

        private fun pkcs7Unpad(input: ByteArray): ByteArray {
            val padding = input.last().toInt() and 0xff
            require(padding in 1..input.size) { "填充长度非法" }
            return input.copyOfRange(0, input.size - padding)
        }
    }
}

