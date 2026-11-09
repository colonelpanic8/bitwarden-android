package com.x8bit.bitwarden.data.auth.manager.model

/**
 * The result of checking which passkeys a security key holds.
 */
sealed class PasskeyProbeResult {
    /**
     * The security key was checked.
     *
     * @property credentialIds The base64url-encoded IDs of the requested passkeys the key holds,
     * or `null` if the key does not support checking.
     * @property isPinNeeded Whether the key needs its PIN to verify the user.
     */
    data class Found(
        val credentialIds: List<String>?,
        val isPinNeeded: Boolean,
    ) : PasskeyProbeResult()

    /**
     * Communicating with the security key failed.
     */
    data class Error(val error: Throwable) : PasskeyProbeResult()
}
