package com.mewbox.tv

import com.mewbox.tv.engine.nodejs.NodeT4Endpoint
import com.mewbox.tv.engine.nodejs.NodeT4Endpoint.NetworkCandidate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class NodeT4ShareTest {

    @Test
    fun testBuildLocalT4Url() {
        assertEquals(
            "http://127.0.0.1:9988/t4/config",
            NodeT4Endpoint.buildLocalT4Url("http://127.0.0.1:9988"),
        )
        assertEquals(
            "http://127.0.0.1:9989/t4/config",
            NodeT4Endpoint.buildLocalT4Url("http://127.0.0.1:9989/"),
        )
        assertEquals(
            "http://127.0.0.1:9988/t4/config",
            NodeT4Endpoint.buildLocalT4Url("http://localhost:9988"),
        )
        assertEquals("", NodeT4Endpoint.buildLocalT4Url(""))
        assertEquals("", NodeT4Endpoint.buildLocalT4Url("   "))
        assertEquals("", NodeT4Endpoint.buildLocalT4Url("not-a-url"))
        assertEquals("", NodeT4Endpoint.buildLocalT4Url("ftp://127.0.0.1:9988"))
    }

    @Test
    fun testBuildLanT4Url() {
        assertEquals(
            "http://192.168.1.50:9988/t4/config",
            NodeT4Endpoint.buildLanT4Url("http://127.0.0.1:9988", "192.168.1.50"),
        )
        assertEquals(
            "http://10.0.0.12:9989/t4/config",
            NodeT4Endpoint.buildLanT4Url("http://127.0.0.1:9989/", "10.0.0.12"),
        )
        assertEquals(
            "https://192.168.31.8:8443/t4/config",
            NodeT4Endpoint.buildLanT4Url("https://127.0.0.1:8443", "192.168.31.8"),
        )
        assertEquals("", NodeT4Endpoint.buildLanT4Url("http://127.0.0.1:9988", null))
        assertEquals("", NodeT4Endpoint.buildLanT4Url("http://127.0.0.1:9988", ""))
        assertEquals("", NodeT4Endpoint.buildLanT4Url("http://127.0.0.1:9988", "999.1.1.1"))
        assertEquals("", NodeT4Endpoint.buildLanT4Url("http://127.0.0.1:9988", "abc"))
        assertEquals("", NodeT4Endpoint.buildLanT4Url("", "192.168.1.50"))
    }

    @Test
    fun testSelectPreferredLanIpv4Priority() {
        val candidates = listOf(
            NetworkCandidate("lo", "127.0.0.1", isUp = true, isLoopback = true),
            NetworkCandidate("rmnet_data0", "10.120.5.4"),
            NetworkCandidate("tun0", "192.168.10.2"),
            NetworkCandidate("ccmni0", "172.16.0.8"),
            NetworkCandidate("dummy0", "192.168.2.2"),
            NetworkCandidate("v4-rmnet_data1", "192.0.0.4"),
            NetworkCandidate("eth0", "192.168.1.10"),
            NetworkCandidate("wlan1", "172.16.5.2"),
            NetworkCandidate("wlan0", "10.0.0.9"),
            NetworkCandidate("ap0", "192.168.43.1"),
        )
        assertEquals("192.168.43.1", NodeT4Endpoint.selectPreferredLanIpv4(candidates))

        val wlanPrivateOrder = listOf(
            NetworkCandidate("wlan0", "172.20.10.2"),
            NetworkCandidate("wlan1", "10.0.0.5"),
        )
        assertEquals("10.0.0.5", NodeT4Endpoint.selectPreferredLanIpv4(wlanPrivateOrder))

        val ethVsOther = listOf(
            NetworkCandidate("usb0", "192.168.42.129"),
            NetworkCandidate("eth0", "172.16.1.10"),
        )
        assertEquals("172.16.1.10", NodeT4Endpoint.selectPreferredLanIpv4(ethVsOther))
    }

    @Test
    fun testSelectPreferredLanIpv4FiltersUnusable() {
        val unusable = listOf(
            NetworkCandidate("wlan0", "127.0.0.1"),
            NetworkCandidate("wlan0", "169.254.12.34"),
            NetworkCandidate("wlan0", "0.0.0.0"),
            NetworkCandidate("wlan0", "192.168.1.20", isUp = false),
            NetworkCandidate("wlan0", "192.168.1.21", isLoopback = true),
            NetworkCandidate("tun0", "192.168.1.22"),
            NetworkCandidate("tap0", "192.168.1.23"),
            NetworkCandidate("ppp0", "192.168.1.24"),
            NetworkCandidate("rmnet_data0", "10.10.10.10"),
        )
        assertNull(NodeT4Endpoint.selectPreferredLanIpv4(unusable))
    }
}

