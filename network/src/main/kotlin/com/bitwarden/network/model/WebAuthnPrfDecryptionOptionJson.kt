package com.bitwarden.network.model

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonNames

/**
 * A passkey that can unlock the vault using the WebAuthn PRF extension.
 *
 * @property credentialId The base64url-encoded ID of the passkey credential.
 * @property transports The transports the authenticator supports, if known.
 * @property encryptedPrivateKey The private key of the passkey's key set, encrypted with the key
 * derived from the PRF output.
 * @property encryptedUserKey The user key, encrypted with the key set's public key.
 */
@OptIn(ExperimentalSerializationApi::class)
@Serializable
data class WebAuthnPrfDecryptionOptionJson(
    @SerialName("credentialId")
    @JsonNames("CredentialId")
    val credentialId: String?,

    @SerialName("transports")
    @JsonNames("Transports")
    val transports: List<String>?,

    @SerialName("encryptedPrivateKey")
    @JsonNames("EncryptedPrivateKey")
    val encryptedPrivateKey: String,

    @SerialName("encryptedUserKey")
    @JsonNames("EncryptedUserKey")
    val encryptedUserKey: String,
)
