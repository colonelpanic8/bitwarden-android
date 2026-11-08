package com.x8bit.bitwarden.data.auth.manager.model

/**
 * The parameters for getting a passkey assertion from a security key.
 *
 * @property challenge The base64url-encoded challenge.
 * @property relyingPartyId The relying party ID.
 * @property origin The origin the assertion is made for, e.g. `https://vault.example.com`.
 * @property allowCredentialIds The base64url-encoded credential IDs that may be used, or empty
 * for a discoverable credential.
 * @property prfSalt The base64url-encoded WebAuthn PRF input to evaluate.
 * @property timeoutMillis The timeout for the request, if any.
 */
data class PasskeyAssertionRequest(
    val challenge: String,
    val relyingPartyId: String,
    val origin: String,
    val allowCredentialIds: List<String>,
    val prfSalt: String,
    val timeoutMillis: Long?,
)
