package com.fongmi.nodejs

import java.io.File
import java.io.IOException
import java.nio.charset.StandardCharsets
import java.security.KeyFactory
import java.security.MessageDigest
import java.security.Signature
import java.security.interfaces.RSAPublicKey
import java.security.spec.X509EncodedKeySpec
import java.util.Base64
import java.util.Locale
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive

internal class NodeManifest private constructor(
    private val raw: String,
    private val indexSha256: String,
    private val configSha256: String,
    private val publicKey: ByteArray,
    private val signature: ByteArray,
) {
    companion object {
        const val FILE_NAME = "index.manifest.json"
        private const val ALGORITHM = "SHA256withRSA"
        private const val DOMAIN = "catvod-node-bundle-v1\n"

        @JvmStatic
        @Throws(IOException::class)
        fun parse(text: String): NodeManifest {
            try {
                val element = Json.parseToJsonElement(text)
                val obj = element as? JsonObject
                    ?: throw IOException("Node manifest is not a JSON object")
                val version = obj["version"]?.jsonPrimitive?.intOrNull
                if (version != 1) {
                    throw IOException("Unsupported Node manifest version")
                }
                val algorithm = obj["algorithm"]?.jsonPrimitive?.content
                if (!ALGORITHM.equals(algorithm, ignoreCase = true)) {
                    throw IOException("Unsupported Node manifest algorithm")
                }
                return readManifest(text, obj)
            } catch (e: IOException) {
                throw e
            } catch (e: Exception) {
                throw IOException("Invalid Node manifest", e)
            }
        }

        private fun readManifest(text: String, obj: JsonObject): NodeManifest {
            val indexSha256 = digest(obj, "indexSha256")
            val configSha256 = digest(obj, "configSha256")
            val pubKeyStr = obj["publicKey"]?.jsonPrimitive?.content
                ?: throw IOException("Node manifest missing publicKey")
            val sigStr = obj["signature"]?.jsonPrimitive?.content
                ?: throw IOException("Node manifest missing signature")
            val publicKey = Base64.getDecoder().decode(pubKeyStr)
            val signature = Base64.getDecoder().decode(sigStr)
            if (publicKey.isEmpty() || signature.isEmpty()) {
                throw IOException("Node manifest key or signature is empty")
            }
            return NodeManifest(text, indexSha256, configSha256, publicKey, signature)
        }

        private fun verifyDigest(file: File, expected: String, name: String) {
            if (!MessageDigest.isEqual(
                    expected.toByteArray(StandardCharsets.US_ASCII),
                    sha256(file).toByteArray(StandardCharsets.US_ASCII)
                )
            ) {
                throw IOException("Node manifest $name SHA-256 mismatch")
            }
        }

        private fun sha256(file: File): String = Crypto.toHex(Crypto.sha256(file))

        private fun digest(obj: JsonObject, name: String): String {
            val value = obj[name]?.jsonPrimitive?.content?.lowercase(Locale.ROOT)
                ?: throw IOException("Node manifest missing $name")
            if (!value.matches(Regex("[a-f0-9]{64}"))) throw IOException("Invalid Node manifest $name")
            return value
        }

        private fun payload(indexSha256: String, configSha256: String): ByteArray {
            return (DOMAIN + indexSha256.lowercase(Locale.ROOT) + "\n" + configSha256.lowercase(Locale.ROOT) + "\n")
                .toByteArray(StandardCharsets.US_ASCII)
        }
    }

    @Throws(IOException::class)
    fun verify(index: File, config: File, trustedFingerprint: String) {
        verifyDigest(index, indexSha256, "index")
        verifyDigest(config, configSha256, "config")
        val fingerprint = fingerprint()
        if (trustedFingerprint.isNotEmpty() && !MessageDigest.isEqual(
                trustedFingerprint.toByteArray(StandardCharsets.US_ASCII),
                fingerprint.toByteArray(StandardCharsets.US_ASCII)
            )
        ) {
            throw IOException("Node bundle signing key changed")
        }
        verifySignature()
    }

    private fun verifySignature() {
        try {
            val key = KeyFactory.getInstance("RSA").generatePublic(X509EncodedKeySpec(publicKey))
            if (key !is RSAPublicKey || key.modulus.bitLength() < 2048) {
                throw IOException("Node manifest RSA key is too small")
            }
            val verifier = Signature.getInstance(ALGORITHM)
            verifier.initVerify(key)
            verifier.update(payload(indexSha256, configSha256))
            if (!verifier.verify(signature)) throw IOException("Invalid Node manifest signature")
        } catch (e: IOException) {
            throw e
        } catch (e: Exception) {
            throw IOException("Unable to verify Node manifest", e)
        }
    }

    fun raw(): String = raw

    fun fingerprint(): String = Crypto.toHex(Crypto.newDigest("SHA-256").digest(publicKey))
}

