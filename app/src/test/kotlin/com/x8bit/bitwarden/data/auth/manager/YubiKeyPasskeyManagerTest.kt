package com.x8bit.bitwarden.data.auth.manager

import com.yubico.yubikit.fido.ctap.PinUvAuthProtocolV1
import com.yubico.yubikit.fido.ctap.PinUvAuthProtocolV2
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class YubiKeyPasskeyManagerTest {

    @Test
    fun `requiresClientPin should only be true for PIN-only authenticators`() {
        // YubiKey 5: a PIN and no built-in user verification.
        assertTrue(mapOf("rk" to true, "up" to true, "clientPin" to true).requiresClientPin())
        // Watch authenticator: built-in user verification and no PIN.
        assertFalse(mapOf("rk" to true, "up" to true, "uv" to true).requiresClientPin())
        // Biometric key with a PIN as fallback: built-in verification is used.
        assertFalse(mapOf("uv" to true, "clientPin" to true).requiresClientPin())
        // No user verification configured at all; the assertion reports it.
        assertFalse(mapOf("uv" to false, "clientPin" to false).requiresClientPin())
    }

    @Test
    fun `toPinUvAuthProtocolOrNull should prefer version 2 and fall back to version 1`() {
        assertEquals(PinUvAuthProtocolV2.VERSION, listOf(1, 2).toPinUvAuthProtocolOrNull()?.version)
        assertEquals(PinUvAuthProtocolV1.VERSION, listOf(1).toPinUvAuthProtocolOrNull()?.version)
        assertNull(emptyList<Int>().toPinUvAuthProtocolOrNull())
    }
}
