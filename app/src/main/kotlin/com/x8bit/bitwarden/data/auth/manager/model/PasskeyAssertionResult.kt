package com.x8bit.bitwarden.data.auth.manager.model

/**
 * The result of getting a passkey assertion from a security key.
 */
sealed class PasskeyAssertionResult {
    /**
     * The security key returned an assertion.
     *
     * @property credentialId The base64url-encoded ID of the credential used.
     * @property deviceResponse The assertion as WebAuthn `PublicKeyCredential` JSON, without
     * client extension results.
     * @property prfOutput The base64url-encoded PRF output, if the credential supports PRF. This
     * is secret and must never be sent to the server.
     */
    data class Success(
        val credentialId: String,
        val deviceResponse: String,
        val prfOutput: String?,
    ) : PasskeyAssertionResult()

    /**
     * The PIN was wrong.
     */
    data class InvalidPin(val retriesRemaining: Int) : PasskeyAssertionResult()

    /**
     * The security key has no matching passkey.
     */
    data object NoCredentials : PasskeyAssertionResult()

    /**
     * Communicating with the security key failed.
     */
    data class Error(val error: Throwable) : PasskeyAssertionResult()
}
