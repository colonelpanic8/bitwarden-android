package com.x8bit.bitwarden.data.auth.manager

import com.bitwarden.network.service.AccountsService
import com.x8bit.bitwarden.data.auth.datasource.disk.AuthDiskSource
import com.x8bit.bitwarden.data.auth.datasource.disk.KnownPasskeyDiskSource
import com.x8bit.bitwarden.data.auth.datasource.disk.model.KnownPasskeyJson
import com.x8bit.bitwarden.data.auth.manager.util.toBase64Url
import com.x8bit.bitwarden.data.auth.repository.util.allWebAuthnPrfOptions
import com.x8bit.bitwarden.data.auth.repository.util.decodeBase64UrlOrNull
import com.x8bit.bitwarden.data.platform.error.NoActiveUserException
import java.time.Clock

/**
 * Default implementation of [KnownPasskeyManager].
 */
class KnownPasskeyManagerImpl(
    private val knownPasskeyDiskSource: KnownPasskeyDiskSource,
    private val authDiskSource: AuthDiskSource,
    private val accountsService: AccountsService,
    private val clock: Clock,
) : KnownPasskeyManager {

    override val knownPasskeys: List<KnownPasskeyJson>
        get() = knownPasskeyDiskSource.knownPasskeys

    override fun recordUse(credentialId: String, relyingPartyId: String) {
        val profile = authDiskSource.userState?.activeAccount?.profile ?: return
        upsert(
            credentialId = credentialId,
            relyingPartyId = relyingPartyId,
            userId = profile.userId,
        ) { existing ->
            existing.copy(
                email = profile.email,
                lastUsedEpochMillis = clock.millis(),
            )
        }
    }

    override suspend fun refreshNames(relyingPartyId: String): Result<Unit> {
        val profile = authDiskSource.userState?.activeAccount?.profile
            ?: return Result.failure(NoActiveUserException())
        val options = profile.userDecryptionOptions?.allWebAuthnPrfOptions.orEmpty()
        if (options.isEmpty()) return Result.success(Unit)
        return accountsService
            .getWebAuthnCredentials()
            .map { response ->
                val namesByUserKey = response
                    .credentials
                    .mapNotNull { credential ->
                        credential.encryptedUserKey?.let { it to credential.name }
                    }
                    .toMap()
                options.forEach { option ->
                    val credentialId = option.credentialId ?: return@forEach
                    upsert(
                        credentialId = credentialId,
                        relyingPartyId = relyingPartyId,
                        userId = profile.userId,
                    ) { existing ->
                        existing.copy(
                            email = profile.email,
                            name = namesByUserKey[option.encryptedUserKey] ?: existing.name,
                        )
                    }
                }
            }
    }

    private fun upsert(
        credentialId: String,
        relyingPartyId: String,
        userId: String,
        update: (KnownPasskeyJson) -> KnownPasskeyJson,
    ) {
        val id = credentialId.toCanonicalCredentialId()
        val passkeys = knownPasskeyDiskSource.knownPasskeys
        val existing = passkeys.firstOrNull { it.credentialId == id && it.userId == userId }
            ?: KnownPasskeyJson(
                credentialId = id,
                relyingPartyId = relyingPartyId,
                userId = userId,
                email = null,
                name = null,
                lastUsedEpochMillis = null,
            )
        knownPasskeyDiskSource.knownPasskeys = passkeys.filterNot { it == existing } +
            update(existing).copy(relyingPartyId = relyingPartyId)
    }
}

/**
 * This credential ID as unpadded base64url, so IDs from the server and from authenticators
 * compare equal.
 */
fun String.toCanonicalCredentialId(): String = decodeBase64UrlOrNull()?.toBase64Url() ?: this
