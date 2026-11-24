package com.mewbox.tv.engine.nodejs

import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import timber.log.Timber
import java.net.Inet4Address
import java.net.NetworkInterface

/**
 * Node.js 暴露的 T4 订阅入口地址构建与本机局域网 IPv4 解析工具。
 */
object NodeT4Endpoint {

    const val T4_CONFIG_PATH = "/t4/config"

    private val EXCLUDED_IFACE_PREFIXES = listOf("lo", "tun", "tap", "ppp", "rmnet", "ccmni", "dummy", "v4-")

    data class NetworkCandidate(
        val interfaceName: String,
        val ip: String,
        val isUp: Boolean = true,
        val isLoopback: Boolean = false,
    )

    fun buildLocalT4Url(baseAddress: String): String {
        val parsed = baseAddress.trim().toHttpUrlOrNull() ?: return ""
        val host = if (parsed.host.equals("localhost", ignoreCase = true)) "127.0.0.1" else parsed.host
        return parsed.newBuilder()
            .host(host)
            .encodedPath(T4_CONFIG_PATH)
            .query(null)
            .fragment(null)
            .build()
            .toString()
    }

    fun buildLanT4Url(baseAddress: String, lanIp: String?): String {
        val parsed = baseAddress.trim().toHttpUrlOrNull() ?: return ""
        val ip = lanIp?.trim()?.takeIf { parseIpv4Octets(it) != null } ?: return ""
        return parsed.newBuilder()
            .host(ip)
            .encodedPath(T4_CONFIG_PATH)
            .query(null)
            .fragment(null)
            .build()
            .toString()
    }

    fun selectPreferredLanIpv4(candidates: List<NetworkCandidate>): String? {
        var bestIp: String? = null
        var bestScore = Int.MIN_VALUE

        for (candidate in candidates) {
            if (!candidate.isUp || candidate.isLoopback) continue
            val iface = candidate.interfaceName.trim().lowercase()
            if (EXCLUDED_IFACE_PREFIXES.any { iface.startsWith(it) }) continue

            val ip = candidate.ip.trim()
            val octets = parseIpv4Octets(ip) ?: continue
            if (isUnusableLanOctets(octets)) continue

            val privateRank = privateSegmentRank(octets)
            val score = scoreCandidate(iface, privateRank)
            if (score > bestScore) {
                bestScore = score
                bestIp = ip
            }
        }
        return bestIp
    }

    fun resolveLanIpv4(): String? = try {
        val interfaces = NetworkInterface.getNetworkInterfaces() ?: return null
        val candidates = mutableListOf<NetworkCandidate>()
        while (interfaces.hasMoreElements()) {
            val nif = interfaces.nextElement()
            val isUp = nif.isUp
            val isLoopback = nif.isLoopback
            val addresses = nif.inetAddresses
            while (addresses.hasMoreElements()) {
                val addr = addresses.nextElement()
                if (addr is Inet4Address) {
                    val ip = addr.hostAddress ?: continue
                    candidates.add(
                        NetworkCandidate(
                            interfaceName = nif.name.orEmpty(),
                            ip = ip,
                            isUp = isUp,
                            isLoopback = isLoopback,
                        ),
                    )
                }
            }
        }
        selectPreferredLanIpv4(candidates)
    } catch (e: Exception) {
        Timber.w(e, "解析本机局域网 IPv4 失败")
        null
    }

    private fun parseIpv4Octets(ip: String): IntArray? {
        val parts = ip.split('.')
        if (parts.size != 4) return null
        val octets = IntArray(4)
        for (i in 0 until 4) {
            val part = parts[i]
            if (part.isEmpty() || (part.length > 1 && part[0] == '0')) return null
            val num = part.toIntOrNull() ?: return null
            if (num !in 0..255) return null
            octets[i] = num
        }
        return octets
    }

    private fun isUnusableLanOctets(octets: IntArray): Boolean {
        if (octets[0] == 127 || octets[0] == 0) return true
        return octets[0] == 169 && octets[1] == 254
    }

    private fun privateSegmentRank(octets: IntArray): Int = when {
        octets[0] == 192 && octets[1] == 168 -> 3
        octets[0] == 10 -> 2
        octets[0] == 172 && octets[1] in 16..31 -> 1
        else -> 0
    }

    private fun scoreCandidate(iface: String, privateRank: Int): Int {
        val tier = when {
            privateRank > 0 && (iface.startsWith("wlan") || iface.startsWith("ap")) -> 3
            privateRank > 0 && iface.startsWith("eth") -> 2
            privateRank > 0 -> 1
            else -> 0
        }
        return tier * 10 + privateRank
    }
}

