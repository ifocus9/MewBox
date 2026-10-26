package com.mewbox.tv.api.net

import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Interceptor
import okhttp3.Response
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 将配置/播放地址中的 127.0.0.1 / localhost 重写为用户设置的目标主机
 * （局域网 IP、模拟器 10.0.2.2；目标可带端口）。为空则不改写。
 * 真机联调更推荐 `adb reverse tcp:9988 tcp:9988`，此时无需任何映射。
 */
@Singleton
class HostMapping @Inject constructor() {
    @Volatile
    var target: String = ""

    fun rewrite(url: HttpUrl): HttpUrl {
        val t = target.trim()
        if (t.isEmpty() || !isLoopback(url.host)) return url
        val mapped = "http://$t".toHttpUrlOrNull() ?: return url
        val builder = url.newBuilder().host(mapped.host)
        if (t.contains(':')) builder.port(mapped.port)
        return builder.build()
    }

    fun rewrite(url: String): String {
        val parsed = url.toHttpUrlOrNull() ?: return url
        return rewrite(parsed).toString()
    }

    private fun isLoopback(host: String) = host == "127.0.0.1" || host == "localhost"
}

class HostInterceptor(private val mapping: HostMapping) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val newUrl = mapping.rewrite(request.url)
        return if (newUrl == request.url) chain.proceed(request)
        else chain.proceed(request.newBuilder().url(newUrl).build())
    }
}
