package com.bitwarden.network.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * The response for a request to log in with a passkey.
 *
 * @property options The WebAuthn assertion options to pass to the authenticator.
 * @property token The token identifying the challenge, sent back with the assertion.
 */
@Serializable
data class WebAuthnLoginAssertionOptionsResponseJson(
    @SerialName("options")
    val options: Options,

    @SerialName("token")
    val token: String,
) {
    /**
     * The WebAuthn `PublicKeyCredentialRequestOptions` for a passkey login.
     *
     * @property challenge The base64url-encoded challenge.
     * @property relyingPartyId The relying party ID.
     * @property timeout The timeout in milliseconds, if any.
     * @property userVerification The user verification requirement, if any.
     */
    @Serializable
    data class Options(
        @SerialName("challenge")
        val challenge: String,

        @SerialName("rpId")
        val relyingPartyId: String,

        @SerialName("timeout")
        val timeout: Long? = null,

        @SerialName("userVerification")
        val userVerification: String? = null,
    )
}
