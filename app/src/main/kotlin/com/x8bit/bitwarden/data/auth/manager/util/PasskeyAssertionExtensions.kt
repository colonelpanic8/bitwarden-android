package com.x8bit.bitwarden.data.auth.manager.util

import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.net.URI
import java.net.URISyntaxException
import java.util.Base64

/**
 * Encodes bytes as unpadded base64url.
 */
fun ByteArray.toBase64Url(): String = Base64.getUrlEncoder().withoutPadding().encodeToString(this)

/**
 * Returns the WebAuthn origin (`scheme://host[:port]`) of this URL, or `null` if it is not an
 * absolute URL.
 */
fun String.toWebAuthnOriginOrNull(): String? {
    val uri = try {
        URI(this.trim())
    } catch (_: URISyntaxException) {
        return null
    }
    val scheme = uri.scheme?.lowercase() ?: return null
    val host = uri.host?.lowercase() ?: return null
    return if (uri.port == -1) "$scheme://$host" else "$scheme://$host:${uri.port}"
}

/**
 * Builds the `clientDataJSON` for a WebAuthn assertion with the base64url [challenge] made for
 * [origin].
 */
fun buildWebAuthnGetClientDataJson(challenge: String, origin: String): String =
    buildJsonObject {
        put("type", "webauthn.get")
        put("challenge", challenge)
        put("origin", origin)
        put("crossOrigin", false)
    }
        .toString()

/**
 * Serializes an assertion as WebAuthn `PublicKeyCredential` JSON, the format Bitwarden servers
 * accept as the `deviceResponse` of a passkey login.
 */
@Suppress("LongParameterList")
fun buildWebAuthnAssertionDeviceResponse(
    rawId: ByteArray,
    authenticatorData: ByteArray,
    clientDataJson: ByteArray,
    signature: ByteArray,
    userHandle: ByteArray?,
): String =
    buildJsonObject {
        put("id", rawId.toBase64Url())
        put("rawId", rawId.toBase64Url())
        put("type", "public-key")
        put("extensions", JsonObject(emptyMap()))
        put(
            "response",
            buildJsonObject {
                put("authenticatorData", authenticatorData.toBase64Url())
                put("clientDataJSON", clientDataJson.toBase64Url())
                put("signature", signature.toBase64Url())
                put("userHandle", userHandle?.toBase64Url()?.let(::JsonPrimitive) ?: JsonNull)
            },
        )
    }
        .toString()
