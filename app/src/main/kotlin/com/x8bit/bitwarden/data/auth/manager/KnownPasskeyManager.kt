package com.x8bit.bitwarden.data.auth.manager

import com.x8bit.bitwarden.data.auth.datasource.disk.model.KnownPasskeyJson

/**
 * Remembers the passkeys used on this device and their names, so they can be listed when choosing
 * a passkey.
 */
interface KnownPasskeyManager {
    /**
     * The known passkeys of every account.
     */
    val knownPasskeys: List<KnownPasskeyJson>

    /**
     * Records that the active account just logged in or unlocked with the passkey
     * [credentialId] for [relyingPartyId].
     */
    fun recordUse(credentialId: String, relyingPartyId: String)

    /**
     * Adds the active account's passkeys that can unlock the vault, named as on the server.
     */
    suspend fun refreshNames(relyingPartyId: String): Result<Unit>
}
