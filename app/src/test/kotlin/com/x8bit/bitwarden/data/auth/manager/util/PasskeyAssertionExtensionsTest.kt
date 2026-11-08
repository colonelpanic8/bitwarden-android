package com.x8bit.bitwarden.data.auth.manager.util

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class PasskeyAssertionExtensionsTest {

    @Test
    fun `toWebAuthnOriginOrNull should keep scheme, host and explicit port only`() {
        assertEquals(
            "https://jimi-hendnix.taileb3aad.ts.net:8222",
            "https://Jimi-Hendnix.taileb3aad.ts.net:8222/#/vault".toWebAuthnOriginOrNull(),
        )
        assertEquals(
            "https://vault.example.com",
            "https://vault.example.com/".toWebAuthnOriginOrNull(),
        )
        assertNull("vault.example.com".toWebAuthnOriginOrNull())
    }

    @Test
    fun `buildWebAuthnGetClientDataJson should produce the WebAuthn get client data`() {
        assertEquals(
            """{"type":"webauthn.get","challenge":"Y2hhbGxlbmdl",""" +
                """"origin":"https://vault.example.com","crossOrigin":false}""",
            buildWebAuthnGetClientDataJson(
                challenge = "Y2hhbGxlbmdl",
                origin = "https://vault.example.com",
            ),
        )
    }

    @Test
    fun `buildWebAuthnAssertionDeviceResponse should encode the assertion as base64url`() {
        val response = Json.parseToJsonElement(
            buildWebAuthnAssertionDeviceResponse(
                rawId = byteArrayOf(-5, -1, -2),
                authenticatorData = byteArrayOf(1, 2, 3),
                clientDataJson = "{}".toByteArray(),
                signature = byteArrayOf(4, 5),
                userHandle = byteArrayOf(-1),
            ),
        )

        assertEquals(
            JsonObject(
                mapOf(
                    "id" to JsonPrimitive("-__-"),
                    "rawId" to JsonPrimitive("-__-"),
                    "type" to JsonPrimitive("public-key"),
                    "extensions" to JsonObject(emptyMap()),
                    "response" to JsonObject(
                        mapOf(
                            "authenticatorData" to JsonPrimitive("AQID"),
                            "clientDataJSON" to JsonPrimitive("e30"),
                            "signature" to JsonPrimitive("BAU"),
                            "userHandle" to JsonPrimitive("_w"),
                        ),
                    ),
                ),
            ),
            response,
        )
    }

    @Test
    fun `buildWebAuthnAssertionDeviceResponse should send a null user handle when absent`() {
        val response = Json.parseToJsonElement(
            buildWebAuthnAssertionDeviceResponse(
                rawId = byteArrayOf(1),
                authenticatorData = byteArrayOf(1),
                clientDataJson = byteArrayOf(1),
                signature = byteArrayOf(1),
                userHandle = null,
            ),
        ) as JsonObject

        assertEquals(JsonNull, (response["response"] as JsonObject)["userHandle"])
    }
}
