package com.x8bit.bitwarden.data.auth.datasource.disk.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * A passkey this device has seen for an account.
 *
 * @property credentialId The base64url-encoded credential ID, without padding.
 * @property relyingPartyId The relying party ID the passkey belongs to.
 * @property userId The ID of the account the passkey belongs to.
 * @property email The email of the account the passkey belongs to, if known.
 * @property name The name the user gave the passkey on the server, if known.
 * @property lastUsedEpochMillis When the passkey was last used on this device, if ever.
 */
@Serializable
data class KnownPasskeyJson(
    @SerialName("credentialId")
    val credentialId: String,

    @SerialName("relyingPartyId")
    val relyingPartyId: String,

    @SerialName("userId")
    val userId: String,

    @SerialName("email")
    val email: String?,

    @SerialName("name")
    val name: String?,

    @SerialName("lastUsedEpochMillis")
    val lastUsedEpochMillis: Long?,
)
