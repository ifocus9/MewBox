package com.mewbox.tv.api.net

import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.IOException
import java.util.concurrent.TimeUnit

class T4ServiceTest {

    @Test
    fun `get sends encoded query parameters and returns body`() = runBlocking {
        val server = MockWebServer()
        server.enqueue(MockResponse().setResponseCode(200).setBody("{\"ok\":true}"))
        server.start()
        try {
            val service = T4Service(OkHttpClient())
            val body = service.get(
                url = server.url("/api/vod").toString(),
                params = mapOf("play" to "a/b c&d=1", "flag" to "线路 1"),
            ).use { it.string() }

            assertEquals("{\"ok\":true}", body)
            val recorded = server.takeRequest()
            assertEquals("GET", recorded.method)
            assertEquals("/api/vod", recorded.requestUrl?.encodedPath)
            assertEquals("a/b c&d=1", recorded.requestUrl?.queryParameter("play"))
            assertEquals("线路 1", recorded.requestUrl?.queryParameter("flag"))
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun `get throws IOException on non-2xx response`() = runBlocking {
        val server = MockWebServer()
        server.enqueue(MockResponse().setResponseCode(502).setBody("bad gateway"))
        server.start()
        try {
            val service = T4Service(OkHttpClient())
            try {
                service.get(server.url("/fail").toString()).use { it.string() }
                fail("Expected IOException")
            } catch (e: IOException) {
                assertTrue(e.message.orEmpty().contains("HTTP 502"))
            }
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun `get cancels underlying okhttp call when coroutine cancels`() = runBlocking {
        val server = MockWebServer()
        server.enqueue(MockResponse().setHeadersDelay(5, TimeUnit.SECONDS).setBody("late"))
        server.start()
        val client = OkHttpClient()
        try {
            val service = T4Service(client)
            val job = async {
                service.get(server.url("/slow").toString()).use { it.string() }
            }
            delay(50)
            job.cancel()
            job.join()
            assertTrue(job.isCancelled)
            assertEquals(0, client.dispatcher.runningCallsCount())
        } finally {
            server.shutdown()
        }
    }
}

