package com.mewbox.tv

import android.content.Context
import com.mewbox.tv.api.net.LocalMessageBridge
import com.mewbox.tv.bean.WebOptions
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.atomic.AtomicReference

class LocalMessageBridgeTest {

    @Test
    fun testWebOptionsParsing() {
        val json = """{"url":"https://cookie.900730.xyz","mode":"browser","cookie":[{"url":"https://cookie.900730.xyz","keys":["session","uid"]}]}"""
        val options = WebOptions.fromJsonString(json)

        assertTrue(options.isValid())
        assertEquals("https://cookie.900730.xyz", options.url)
        assertEquals("browser", options.mode)
        assertEquals(1, options.cookie.size)
        assertEquals(listOf("session", "uid"), options.cookie[0].keys)
    }

    @Test
    fun testWebOptionsSimpleUrl() {
        val simple = "https://cookie.900730.xyz"
        val options = WebOptions.fromJsonString(simple)

        assertTrue(options.isValid())
        assertEquals("https://cookie.900730.xyz", options.url)
    }

    @Test
    fun testBridgeHttpServerPostMsg() {
        // 创建一个模拟 context 的 bridge（避免在纯 JVM 单元测试中访问 Android Context）
        val bridge = LocalMessageBridge(DummyContext)
        val port = bridge.start(0) // 使用动态端口测试
        assertTrue(port > 0)

        val capturedAction = AtomicReference<String>()
        val capturedUrl = AtomicReference<String>()

        bridge.addHandler(object : LocalMessageBridge.MessageHandler {
            override fun onMessage(action: String, opt: JsonElement?): String? {
                if (action == "openInternalWebview" || action == "openWeb") {
                    capturedAction.set(action)
                    val url = ((opt as? JsonObject)?.get("url") as? JsonPrimitive)?.content
                    capturedUrl.set(url)
                    return """{"success":true,"code":0}"""
                }
                return null
            }
        })

        try {
            val client = OkHttpClient()
            val requestJson = """
                {
                    "action": "openInternalWebview",
                    "opt": {
                        "url": "https://cookie.900730.xyz"
                    }
                }
            """.trimIndent()

            val request = Request.Builder()
                .url("http://127.0.0.1:$port/msg")
                .post(requestJson.toRequestBody("application/json".toMediaType()))
                .build()

            val response = client.newCall(request).execute()
            assertEquals(200, response.code)
            val respBody = response.body?.string().orEmpty()
            assertTrue(respBody.contains(""""success":true"""))

            assertEquals("openInternalWebview", capturedAction.get())
            assertEquals("https://cookie.900730.xyz", capturedUrl.get())
        } finally {
            bridge.stop()
        }
    }

    @Test
    fun testBridgeTokenAuthentication() {
        val bridge = LocalMessageBridge(DummyContext)
        val port = bridge.start(0)
        bridge.token = "secure-token-123"

        try {
            val client = OkHttpClient()
            val requestJson = """{"action":"test"}"""

            // 1. 无 Token 请求 -> 应该返回 403 Forbidden
            val reqNoToken = Request.Builder()
                .url("http://127.0.0.1:$port/msg")
                .post(requestJson.toRequestBody("application/json".toMediaType()))
                .build()
            val respForbidden = client.newCall(reqNoToken).execute()
            assertEquals(403, respForbidden.code)

            // 2. 带正确 Token 请求 -> 应该通过鉴权并返回响应
            val reqWithToken = Request.Builder()
                .url("http://127.0.0.1:$port/msg")
                .addHeader("X-CatVod-Token", "secure-token-123")
                .post(requestJson.toRequestBody("application/json".toMediaType()))
                .build()
            val respOk = client.newCall(reqWithToken).execute()
            assertEquals(200, respOk.code)
        } finally {
            bridge.stop()
        }
    }

    private object DummyContext : android.content.ContextWrapper(null)
}

