package com.x8bit.bitwarden.data.auth.manager

import com.bitwarden.core.data.util.asSuccess
import com.bitwarden.data.datasource.disk.base.FakeSharedPreferences
import com.bitwarden.network.model.WebAuthnCredentialsResponseJson
import com.bitwarden.network.model.WebAuthnPrfDecryptionOptionJson
import com.bitwarden.network.service.AccountsService
import com.x8bit.bitwarden.data.auth.datasource.disk.AuthDiskSource
import com.x8bit.bitwarden.data.auth.datasource.disk.KnownPasskeyDiskSourceImpl
import com.x8bit.bitwarden.data.auth.datasource.disk.model.KnownPasskeyJson
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

class KnownPasskeyManagerTest {

    private val authDiskSource: AuthDiskSource = mockk {
        every { userState } returns mockk {
            every { activeAccount.profile } returns mockk {
                every { userId } returns "user"
                every { email } returns "me@example.com"
                every { userDecryptionOptions } returns mockk {
                    every { webAuthnPrfOptions } returns listOf(
                        prfOption(credentialId = "Y3JlZA==", userKey = "4.yubikey"),
                        prfOption(credentialId = "d2F0Y2g", userKey = "4.watch"),
                    )
                    every { webAuthnPrfOption } returns null
                }
            }
        }
    }
    private val accountsService: AccountsService = mockk {
        coEvery { getWebAuthnCredentials() } returns WebAuthnCredentialsResponseJson(
            credentials = listOf(
                WebAuthnCredentialsResponseJson.Credential(
                    id = "1",
                    name = "Watch",
                    encryptedUserKey = "4.watch",
                ),
                WebAuthnCredentialsResponseJson.Credential(
                    id = "2",
                    name = "No PRF",
                    encryptedUserKey = null,
                ),
            ),
        ).asSuccess()
    }
    private val manager = KnownPasskeyManagerImpl(
        knownPasskeyDiskSource = KnownPasskeyDiskSourceImpl(
            sharedPreferences = FakeSharedPreferences(),
            json = Json,
        ),
        authDiskSource = authDiskSource,
        accountsService = accountsService,
        clock = Clock.fixed(Instant.ofEpochMilli(42L), ZoneOffset.UTC),
    )

    @Test
    fun `refreshNames and recordUse should name passkeys by key set and remember their use`() =
        runTest {
            manager.recordUse(credentialId = "Y3JlZA", relyingPartyId = "vault.example.com")
            manager.refreshNames(relyingPartyId = "vault.example.com")

            assertEquals(
                listOf(
                    KnownPasskeyJson(
                        credentialId = "Y3JlZA",
                        relyingPartyId = "vault.example.com",
                        userId = "user",
                        email = "me@example.com",
                        name = null,
                        lastUsedEpochMillis = 42L,
                    ),
                    KnownPasskeyJson(
                        credentialId = "d2F0Y2g",
                        relyingPartyId = "vault.example.com",
                        userId = "user",
                        email = "me@example.com",
                        name = "Watch",
                        lastUsedEpochMillis = null,
                    ),
                ),
                manager.knownPasskeys,
            )
        }
}

private fun prfOption(credentialId: String, userKey: String) = WebAuthnPrfDecryptionOptionJson(
    credentialId = credentialId,
    transports = null,
    encryptedPrivateKey = "2.privateKey",
    encryptedUserKey = userKey,
)
