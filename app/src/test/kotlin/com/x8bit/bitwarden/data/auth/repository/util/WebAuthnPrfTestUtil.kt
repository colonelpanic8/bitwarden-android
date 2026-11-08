package com.x8bit.bitwarden.data.auth.repository.util

import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Encrypts [plaintext] as a type 2 (AES-256-CBC with HMAC-SHA256) encrypted string with a 64-byte
 * [key], as Bitwarden clients do.
 */
fun createAesCbcHmacEncStringForTest(plaintext: ByteArray, key: ByteArray): String {
    val iv = ByteArray(size = 16) { (it * 3).toByte() }
    val data = Cipher
        .getInstance("AES/CBC/PKCS5Padding")
        .apply { init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, 0, 32, "AES"), IvParameterSpec(iv)) }
        .doFinal(plaintext)
    val mac = Mac
        .getInstance("HmacSHA256")
        .apply { init(SecretKeySpec(key, 32, 32, "HmacSHA256")) }
        .doFinal(iv + data)
    val encoder = Base64.getEncoder()
    return "2.${encoder.encodeToString(iv)}|${encoder.encodeToString(data)}|" +
        encoder.encodeToString(mac)
}
