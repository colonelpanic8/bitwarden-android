package com.x8bit.bitwarden.data.auth.manager

import com.x8bit.bitwarden.data.auth.manager.model.PasskeyAssertionRequest
import com.x8bit.bitwarden.data.auth.manager.model.PasskeyAssertionResult
import com.yubico.yubikit.core.YubiKeyDevice

/**
 * Gets passkey assertions from a YubiKey connected over NFC or USB.
 */
interface YubiKeyPasskeyManager {
    /**
     * Gets an assertion for [request] from [device], verifying the user with [pin], and evaluates
     * the PRF extension.
     */
    suspend fun getAssertion(
        device: YubiKeyDevice,
        request: PasskeyAssertionRequest,
        pin: String,
    ): PasskeyAssertionResult
}
