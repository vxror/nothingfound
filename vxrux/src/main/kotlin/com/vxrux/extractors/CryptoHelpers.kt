package com.vxrux.extractors

import android.util.Base64
import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.utils.AppUtils
import java.security.MessageDigest
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Crypto helpers using javax.crypto — replaces the app-internal
 * AesHelper and CryptoJS that use dev.whyoleg.cryptography
 */
object CryptoHelpers {

    data class AesData(
        @JsonProperty("ct") val ct: String,
        @JsonProperty("iv") val iv: String,
        @JsonProperty("s") val s: String,
    )

    fun hexToByteArray(hex: String): ByteArray {
        return hex.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
    }

    /**
     * OpenSSL EVP_BytesToKey compatible — MD5-based key/IV derivation
     */
    fun generateKeyAndIv(
        password: ByteArray,
        salt: ByteArray,
        keyLength: Int = 32,
        ivLength: Int = 16,
    ): Pair<ByteArray, ByteArray>? {
        return try {
            val md5 = MessageDigest.getInstance("MD5")
            val targetSize = keyLength + ivLength
            val generated = mutableListOf<ByteArray>()
            var totalLength = 0
            var previous: ByteArray? = null

            while (totalLength < targetSize) {
                md5.reset()
                if (previous != null) md5.update(previous)
                md5.update(password)
                md5.update(salt)
                val digest = md5.digest()
                generated.add(digest)
                totalLength += digest.size
                previous = digest
            }

            val allBytes = generated.reduce { acc, b -> acc + b }
            return allBytes.copyOfRange(0, keyLength) to allBytes.copyOfRange(keyLength, keyLength + ivLength)
        } catch (_: Exception) { null }
    }

    /**
     * CryptoJS AES handler — decrypt data encrypted with CryptoJS default settings
     */
    fun cryptoAESHandler(data: String, pass: ByteArray, encrypt: Boolean = false, padding: Boolean = true): String? {
        return try {
            val parse = AppUtils.tryParseJson<AesData>(data) ?: return null
            val (key, iv) = generateKeyAndIv(pass, hexToByteArray(parse.s)) ?: return null

            val transformation = if (padding) "AES/CBC/PKCS5Padding" else "AES/CBC/NoPadding"
            val cipher = Cipher.getInstance(transformation)
            cipher.init(
                if (encrypt) Cipher.ENCRYPT_MODE else Cipher.DECRYPT_MODE,
                SecretKeySpec(key, "AES"),
                IvParameterSpec(iv)
            )

            if (!encrypt) {
                val decrypted = cipher.doFinal(Base64.decode(parse.ct, Base64.DEFAULT))
                String(decrypted, Charsets.UTF_8)
            } else {
                val encrypted = cipher.doFinal(parse.ct.toByteArray())
                Base64.encodeToString(encrypted, Base64.DEFAULT)
            }
        } catch (_: Exception) { null }
    }

    /**
     * AES-GCM decryption (for ByseSX)
     */
    fun aesGcmDecrypt(keyBytes: ByteArray, ivBytes: ByteArray, cipherBytes: ByteArray): String? {
        return try {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(keyBytes, "AES"), IvParameterSpec(ivBytes))
            String(cipher.doFinal(cipherBytes), Charsets.UTF_8).removePrefix("﻿")
        } catch (_: Exception) { null }
    }

    fun b64UrlDecode(s: String): ByteArray {
        val fixed = s.replace('-', '+').replace('_', '/')
        val pad = (4 - fixed.length % 4) % 4
        return Base64.decode(fixed + "=".repeat(pad), Base64.DEFAULT)
    }

    fun b64UrlEncode(bytes: ByteArray): String {
        return Base64.encodeToString(bytes, Base64.NO_WRAP or Base64.NO_PADDING or Base64.URL_SAFE)
    }
}
