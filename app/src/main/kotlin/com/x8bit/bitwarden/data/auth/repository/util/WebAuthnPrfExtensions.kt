package com.x8bit.bitwarden.data.auth.repository.util

import com.bitwarden.core.AuthRequestMethod
import com.bitwarden.core.InitUserCryptoMethod
import com.bitwarden.network.model.UserDecryptionOptionsJson
import com.bitwarden.network.model.WebAuthnPrfDecryptionOptionJson
import com.x8bit.bitwarden.data.auth.manager.util.toBase64Url
import java.security.GeneralSecurityException
import java.security.MessageDigest
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

private const val AES_CBC_HMAC_ENC_STRING_TYPE = "2"
private const val AES_CBC_HMAC_ENC_STRING_PART_COUNT = 3
private const val HMAC_SHA256 = "HmacSHA256"
private const val PRF_SECRET_LENGTH = 32
private const val PRF_LOGIN_SALT_INPUT = "passwordless-login"

/**
 * The PRF input Bitwarden clients use for passkey login and unlock: SHA-256 of
 * `"passwordless-login"`.
 */
val webAuthnPrfLoginSalt: ByteArray
    get() = MessageDigest
        .getInstance("SHA-256")
        .digest(PRF_LOGIN_SALT_INPUT.toByteArray(Charsets.UTF_8))

/**
 * Stretches a WebAuthn PRF output into a 64-byte AES-256-CBC + HMAC-SHA256 key, matching
 * `derive_symmetric_key_from_prf` in the Bitwarden SDK: HKDF-Expand with the first 32 bytes as
 * the pseudorandom key and the infos `"enc"` and `"mac"`.
 */
fun deriveWebAuthnPrfKey(prf: ByteArray): ByteArray {
    require(prf.size >= PRF_SECRET_LENGTH) { "PRF output is too short" }
    val secret = prf.copyOf(PRF_SECRET_LENGTH)
    require(secret.any { it != 0.toByte() }) { "PRF output is empty" }
    return hkdfExpandSingleBlock(secret, "enc") + hkdfExpandSingleBlock(secret, "mac")
}

/**
 * Decrypts a type 2 (AES-256-CBC with HMAC-SHA256) encrypted string with a 64-byte key made of
 * the encryption key followed by the MAC key.
 *
 * @throws GeneralSecurityException if the string is malformed or fails authentication.
 */
fun decryptAesCbcHmacEncString(encString: String, key: ByteArray): ByteArray {
    require(key.size == PRF_SECRET_LENGTH * 2) { "Invalid key length" }
    val (iv, data, mac) = encString.parseAesCbcHmacEncStringOrNull()
        ?: throw GeneralSecurityException("Malformed or unsupported encrypted string")
    val expectedMac = Mac
        .getInstance(HMAC_SHA256)
        .apply { init(SecretKeySpec(key, PRF_SECRET_LENGTH, PRF_SECRET_LENGTH, HMAC_SHA256)) }
        .doFinal(iv + data)
    if (!MessageDigest.isEqual(expectedMac, mac)) {
        throw GeneralSecurityException("Encrypted string failed authentication")
    }
    return Cipher
        .getInstance("AES/CBC/PKCS5Padding")
        .apply {
            init(
                Cipher.DECRYPT_MODE,
                SecretKeySpec(key, 0, PRF_SECRET_LENGTH, "AES"),
                IvParameterSpec(iv),
            )
        }
        .doFinal(data)
}

/**
 * Decodes a base64url (or standard base64) string, with or without padding.
 */
fun String.decodeBase64UrlOrNull(): ByteArray? =
    try {
        Base64.getUrlDecoder().decode(this.replace('+', '-').replace('/', '_').trimEnd('='))
    } catch (_: IllegalArgumentException) {
        null
    }

/**
 * Whether this option belongs to the passkey with the given raw [credentialId].
 */
fun WebAuthnPrfDecryptionOptionJson.matchesCredential(credentialId: ByteArray): Boolean =
    this.credentialId?.decodeBase64UrlOrNull()?.contentEquals(credentialId) == true

/**
 * Builds the SDK method that unlocks the vault from this option and the passkey's [prf] output.
 *
 * The option's private key is decrypted with the key derived from the PRF output, and the SDK
 * then decapsulates the user key with it, as it does for an approved auth request.
 */
fun WebAuthnPrfDecryptionOptionJson.toInitUserCryptoMethod(
    prf: ByteArray,
): Result<InitUserCryptoMethod> = runCatching {
    val privateKey = decryptAesCbcHmacEncString(
        encString = encryptedPrivateKey,
        key = deriveWebAuthnPrfKey(prf),
    )
    InitUserCryptoMethod.AuthRequest(
        requestPrivateKey = Base64.getEncoder().encodeToString(privateKey),
        method = AuthRequestMethod.UserKey(protectedUserKey = encryptedUserKey),
    )
}

private fun String.parseAesCbcHmacEncStringOrNull(): List<ByteArray>? {
    if (substringBefore('.', missingDelimiterValue = "") != AES_CBC_HMAC_ENC_STRING_TYPE) {
        return null
    }
    val parts = substringAfter('.')
        .split('|')
        .takeIf { it.size == AES_CBC_HMAC_ENC_STRING_PART_COUNT }
        ?: return null
    val decoder = Base64.getDecoder()
    return try {
        parts.map { decoder.decode(it) }
    } catch (_: IllegalArgumentException) {
        null
    }
}

private fun hkdfExpandSingleBlock(prk: ByteArray, info: String): ByteArray =
    Mac
        .getInstance(HMAC_SHA256)
        .apply { init(SecretKeySpec(prk, HMAC_SHA256)) }
        .doFinal(info.toByteArray(Charsets.UTF_8) + 1.toByte())

/**
 * All of the user's passkeys that can unlock the vault: those from the last sync and the one used
 * to log in.
 */
val UserDecryptionOptionsJson.allWebAuthnPrfOptions: List<WebAuthnPrfDecryptionOptionJson>
    get() = (webAuthnPrfOptions.orEmpty() + listOfNotNull(webAuthnPrfOption))
        .distinctBy { it.credentialId?.decodeBase64UrlOrNull()?.toBase64Url() ?: it.credentialId }
