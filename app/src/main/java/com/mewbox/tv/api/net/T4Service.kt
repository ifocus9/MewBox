package com.mewbox.tv.api.net

import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody
import java.io.IOException
import javax.inject.Inject
import javax.inject.Named
import javax.inject.Singleton
import kotlin.coroutines.resumeWithException

/** T4 与订阅源 HTTP GET 请求：基于 OkHttp 构造动态 URL 与 Query 参数，并支持协程取消时中止请求。 */
@Singleton
class T4Service @Inject constructor(
    @param:Named("api") private val client: OkHttpClient,
) {
    suspend fun get(
        url: String,
        params: Map<String, String> = emptyMap(),
    ): ResponseBody {
        val httpUrl = if (params.isEmpty()) {
            url.toHttpUrl()
        } else {
            url.toHttpUrl().newBuilder().apply {
                for ((k, v) in params) {
                    addQueryParameter(k, v)
                }
            }.build()
        }
        val request = Request.Builder().url(httpUrl).get().build()
        return suspendCancellableCoroutine { cont ->
            val call = client.newCall(request)
            cont.invokeOnCancellation { call.cancel() }
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    if (cont.isActive) cont.resumeWithException(e)
                }

                override fun onResponse(call: Call, response: Response) {
                    if (!cont.isActive) {
                        response.close()
                        return
                    }
                    if (!response.isSuccessful) {
                        val code = response.code
                        response.close()
                        cont.resumeWithException(IOException("HTTP $code"))
                        return
                    }
                    cont.resume(response.body) { _, body, _ -> body.close() }
                }
            })
        }
    }
}
