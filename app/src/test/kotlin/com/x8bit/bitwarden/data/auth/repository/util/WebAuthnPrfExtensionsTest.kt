package com.x8bit.bitwarden.data.auth.repository.util

import com.bitwarden.core.AuthRequestMethod
import com.bitwarden.core.InitUserCryptoMethod
import com.bitwarden.network.model.UserDecryptionOptionsJson
import com.bitwarden.network.model.WebAuthnPrfDecryptionOptionJson
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.security.GeneralSecurityException
import java.security.KeyPairGenerator
import java.util.Base64

class WebAuthnPrfExtensionsTest {

    @Test
    fun `webAuthnPrfLoginSalt should be the SHA-256 of passwordless-login`() {
        assertEquals(
            "l7AG39jbWXuJLJpQNDtSSLpsMoBZ74Es7DR84Nd0OQs",
            Base64.getUrlEncoder().withoutPadding().encodeToString(webAuthnPrfLoginSalt),
        )
    }

    @Test
    fun `deriveWebAuthnPrfKey should match the SDK key stretching`() {
        // Test vector from stretch_key in bitwarden/sdk-internal's bitwarden-crypto.
        val prf = byteArrayOf(
            31, 79, 104, -30, -106, 71, -79, 90, -62, 80, -84, -47, 17, -127, -124, 81,
            -118, -89, 69, -89, -2, -107, 2, 27, 39, -59, 64, 42, 22, -61, 86, 75,
        )
        val expectedEncKey = intArrayOf(
            111, 31, 178, 45, 238, 152, 37, 114, 143, 215, 124, 83, 135, 173, 195, 23,
            142, 134, 120, 249, 61, 132, 163, 182, 113, 197, 189, 204, 188, 21, 237, 96,
        )
        val expectedMacKey = intArrayOf(
            221, 127, 206, 234, 101, 27, 202, 38, 86, 52, 34, 28, 78, 28, 185, 16,
            48, 61, 127, 166, 209, 247, 194, 87, 232, 26, 48, 85, 193, 249, 179, 155,
        )

        val key = deriveWebAuthnPrfKey(prf + ByteArray(size = 8) { 1 })

        assertArrayEquals(
            (expectedEncKey + expectedMacKey).map { it.toByte() }.toByteArray(),
            key,
        )
    }

    @Test
    fun `deriveWebAuthnPrfKey should reject short or empty PRF outputs`() {
        assertThrows<IllegalArgumentException> { deriveWebAuthnPrfKey(ByteArray(size = 16) { 1 }) }
        assertThrows<IllegalArgumentException> { deriveWebAuthnPrfKey(ByteArray(size = 32)) }
    }

    @Test
    fun `decryptAesCbcHmacEncString should decrypt authenticated type 2 strings`() {
        val plaintext = "private key bytes".toByteArray()

        val encString = createAesCbcHmacEncStringForTest(plaintext = plaintext, key = KEY)

        assertArrayEquals(plaintext, decryptAesCbcHmacEncString(encString, KEY))
    }

    @Test
    fun `decryptAesCbcHmacEncString should reject tampered or unsupported strings`() {
        val encString = createAesCbcHmacEncStringForTest("secret".toByteArray(), KEY)
        val (iv, data, mac) = encString.substringAfter('.').split('|')
        val flippedMac = Base64.getDecoder().decode(mac).also { it[0] = (it[0] + 1).toByte() }

        assertThrows<GeneralSecurityException> {
            decryptAesCbcHmacEncString(
                "2.$iv|$data|${Base64.getEncoder().encodeToString(flippedMac)}",
                KEY,
            )
        }
        assertThrows<GeneralSecurityException> {
            decryptAesCbcHmacEncString(encString, KEY.reversedArray())
        }
        assertThrows<GeneralSecurityException> {
            decryptAesCbcHmacEncString(encString.replaceFirst("2.", "0."), KEY)
        }
        assertThrows<GeneralSecurityException> {
            decryptAesCbcHmacEncString("2.$iv|$data", KEY)
        }
    }

    @Test
    fun `toInitUserCryptoMethod should decrypt the key set private key for the SDK`() {
        val prf = ByteArray(size = 32) { it.toByte() }
        val privateKey = KeyPairGenerator
            .getInstance("RSA")
            .apply { initialize(2048) }
            .generateKeyPair()
            .private
            .encoded
        val option = WebAuthnPrfDecryptionOptionJson(
            credentialId = "Y3JlZA",
            transports = null,
            encryptedPrivateKey = createAesCbcHmacEncStringForTest(
                plaintext = privateKey,
                key = deriveWebAuthnPrfKey(prf),
            ),
            encryptedUserKey = "4.encapsulatedUserKey",
        )

        assertEquals(
            InitUserCryptoMethod.AuthRequest(
                requestPrivateKey = Base64.getEncoder().encodeToString(privateKey),
                method = AuthRequestMethod.UserKey(protectedUserKey = "4.encapsulatedUserKey"),
            ),
            option.toInitUserCryptoMethod(prf).getOrThrow(),
        )
        assertTrue(option.toInitUserCryptoMethod(prf.reversedArray()).isFailure)
    }

    @Test
    fun `decodeBase64UrlOrNull should accept url-safe and standard base64`() {
        val bytes = byteArrayOf(-5, -1, -2, 0, 1)

        assertArrayEquals(bytes, "-__-AAE".decodeBase64UrlOrNull())
        assertArrayEquals(bytes, "+//+AAE=".decodeBase64UrlOrNull())
        assertNull("not base64!".decodeBase64UrlOrNull())
    }

    @Test
    fun `matchesCredential should compare decoded credential IDs`() {
        val option = prfOption(credentialId = "-__-AAE")

        assertTrue(option.matchesCredential(byteArrayOf(-5, -1, -2, 0, 1)))
        assertFalse(option.matchesCredential(byteArrayOf(1)))
        assertFalse(prfOption(credentialId = null).matchesCredential(byteArrayOf(1)))
    }

    @Test
    fun `allWebAuthnPrfOptions should combine sync and login options without duplicates`() {
        val synced = prfOption(credentialId = "-__-AAE")
        val other = prfOption(credentialId = "AQI")
        val options = UserDecryptionOptionsJson(
            hasMasterPassword = true,
            masterPasswordUnlock = null,
            trustedDeviceUserDecryptionOptions = null,
            keyConnectorUserDecryptionOptions = null,
            webAuthnPrfOption = prfOption(credentialId = "+//+AAE="),
            webAuthnPrfOptions = listOf(synced, other),
        )

        assertEquals(listOf(synced, other), options.allWebAuthnPrfOptions)
    }

    private fun prfOption(credentialId: String?) = WebAuthnPrfDecryptionOptionJson(
        credentialId = credentialId,
        transports = null,
        encryptedPrivateKey = "2.iv|data|mac",
        encryptedUserKey = "4.userKey",
    )
}

private val KEY = ByteArray(size = 64) { (it * 7 + 1).toByte() }
