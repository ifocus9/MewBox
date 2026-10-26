package com.mewbox.tv.api.net

import okhttp3.ResponseBody
import retrofit2.http.GET
import retrofit2.http.QueryMap
import retrofit2.http.Url

/** T4 请求：源 API 是任意完整 URL，因此使用 @Url 动态地址 + QueryMap。 */
interface T4Service {
    @GET
    suspend fun get(
        @Url url: String,
        @QueryMap params: Map<String, String> = emptyMap(),
    ): ResponseBody
}
