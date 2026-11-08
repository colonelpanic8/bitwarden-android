package com.x8bit.bitwarden.data.auth.repository.model

import com.x8bit.bitwarden.data.auth.manager.model.PasskeyAssertionRequest

/**
 * A challenge for logging in with a passkey.
 *
 * @property token The token identifying the challenge, sent back with the assertion.
 * @property assertionRequest The parameters for getting the assertion from a security key.
 */
data class PasskeyLoginRequest(
    val token: String,
    val assertionRequest: PasskeyAssertionRequest,
)
