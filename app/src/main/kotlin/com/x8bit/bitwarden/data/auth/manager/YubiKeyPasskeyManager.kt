package com.x8bit.bitwarden.data.auth.manager

import com.x8bit.bitwarden.data.auth.manager.model.PasskeyAssertionRequest
import com.x8bit.bitwarden.data.auth.manager.model.PasskeyAssertionResult
import com.x8bit.bitwarden.data.auth.manager.model.PasskeyProbeResult
import com.yubico.yubikit.core.YubiKeyDevice

/**
 * Gets passkey assertions from a YubiKey connected over NFC or USB.
 */
interface YubiKeyPasskeyManager {
    /**
     * Gets an assertion for [request] from [device] and evaluates the PRF extension. User
     * verification is always required: keys with built-in user verification (such as a watch
     * using its screen lock) verify the user themselves and ignore [pin], while other keys need
     * it. If a PIN is needed and [pin] is `null`, [PasskeyAssertionResult.PinRequired] is
     * returned.
     *
     * Any [YubiKeyDevice] that provides a FIDO or smart card connection to a FIDO2 authenticator
     * works, not only YubiKeys.
     */
    suspend fun getAssertion(
        device: YubiKeyDevice,
        request: PasskeyAssertionRequest,
        pin: String?,
    ): PasskeyAssertionResult

    /**
     * Checks which of [credentialIds] for [relyingPartyId] are on [device], without user presence
     * or verification, so the user can be shown which passkeys the key holds.
     */
    suspend fun findCredentials(
        device: YubiKeyDevice,
        relyingPartyId: String,
        credentialIds: List<String>,
    ): PasskeyProbeResult
}
