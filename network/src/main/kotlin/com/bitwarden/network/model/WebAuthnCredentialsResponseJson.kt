package com.bitwarden.network.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Response body for the passkeys the current user can log in with.
 *
 * @property credentials The passkeys.
 */
@Serializable
data class WebAuthnCredentialsResponseJson(
    @SerialName("data")
    val credentials: List<Credential>,
) {
    /**
     * A passkey the user can log in with. It has no credential ID; passkeys that can unlock the
     * vault are matched to the sync's [WebAuthnPrfDecryptionOptionJson] by [encryptedUserKey].
     *
     * @property id The server's ID for the passkey.
     * @property name The name the user gave the passkey.
     * @property encryptedUserKey The user key encrypted with the passkey's key set, if the passkey
     * can unlock the vault.
     */
    @Serializable
    data class Credential(
        @SerialName("id")
        val id: String,

        @SerialName("name")
        val name: String?,

        @SerialName("encryptedUserKey")
        val encryptedUserKey: String?,
    )
}
