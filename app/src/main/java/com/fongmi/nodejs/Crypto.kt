package com.fongmi.nodejs

import java.io.File
import java.io.FileInputStream
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.security.NoSuchAlgorithmException

object Crypto {
    private const val MD5 = "MD5"
    private const val SHA_256 = "SHA-256"
    private val HEX = "0123456789abcdef".toCharArray()

    @JvmStatic
    fun md5(value: String?): String = hash(value, MD5)

    @JvmStatic
    fun md5(file: File): String {
        return try {
            toHex(digest(file, newDigest(MD5)))
        } catch (_: Exception) {
            ""
        }
    }

    @JvmStatic
    fun equals(file: File, expected: String?): Boolean {
        return !expected.isNullOrEmpty() && expected.equals(md5(file), ignoreCase = true)
    }

    @JvmStatic
    fun sha256(file: File): ByteArray = digest(file, newDigest(SHA_256))

    @JvmStatic
    fun sha256(value: String?): String = hash(value, SHA_256)

    @JvmStatic
    fun newDigest(algorithm: String): MessageDigest {
        return try {
            MessageDigest.getInstance(algorithm)
        } catch (e: NoSuchAlgorithmException) {
            throw IllegalStateException(e)
        }
    }

    private fun hash(value: String?, algorithm: String): String {
        if (value == null) return ""
        return toHex(newDigest(algorithm).digest(value.toByteArray(StandardCharsets.UTF_8)))
    }

    private fun digest(file: File, digest: MessageDigest): ByteArray {
        FileInputStream(file).use { input ->
            val buffer = ByteArray(64 * 1024)
            var count: Int
            while (input.read(buffer).also { count = it } != -1) {
                digest.update(buffer, 0, count)
            }
            return digest.digest()
        }
    }

    @JvmStatic
    fun toHex(bytes: ByteArray): String {
        val characters = CharArray(bytes.size * 2)
        for (i in bytes.indices) {
            val value = bytes[i].toInt() and 0xFF
            characters[i * 2] = HEX[value ushr 4]
            characters[i * 2 + 1] = HEX[value and 0x0F]
        }
        return String(characters)
    }
}

