package com.x8bit.bitwarden.ui.auth.feature.passkey

import com.bitwarden.core.data.util.asSuccess
import com.bitwarden.ui.platform.base.BaseViewModelTest
import com.bitwarden.ui.platform.resource.BitwardenString
import com.bitwarden.ui.util.asText
import com.x8bit.bitwarden.data.auth.datasource.disk.model.KnownPasskeyJson
import com.x8bit.bitwarden.data.auth.manager.KnownPasskeyManager
import com.x8bit.bitwarden.data.auth.manager.YubiKeyPasskeyManager
import com.x8bit.bitwarden.data.auth.manager.model.PasskeyAssertionRequest
import com.x8bit.bitwarden.data.auth.manager.model.PasskeyAssertionResult
import com.x8bit.bitwarden.data.auth.manager.model.PasskeyProbeResult
import com.x8bit.bitwarden.data.auth.repository.AuthRepository
import com.x8bit.bitwarden.data.auth.repository.model.LoginResult
import com.x8bit.bitwarden.data.auth.repository.model.PasskeyLoginRequest
import com.x8bit.bitwarden.data.auth.repository.model.UserState
import com.x8bit.bitwarden.data.vault.repository.VaultRepository
import com.x8bit.bitwarden.data.vault.repository.model.VaultUnlockResult
import com.yubico.yubikit.android.transport.nfc.NfcYubiKeyDevice
import com.yubico.yubikit.core.YubiKeyDevice
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.runs
import io.mockk.verify
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class PasskeyViewModelTest : BaseViewModelTest() {

    private val mutableUserStateFlow = MutableStateFlow<UserState?>(
        mockk { every { activeUserId } returns USER_ID },
    )
    private val authRepository: AuthRepository = mockk {
        every { userStateFlow } returns mutableUserStateFlow
        every { getPasskeyUnlockRequest() } returns UNLOCK_REQUEST
    }
    private val vaultRepository: VaultRepository = mockk {
        coEvery {
            unlockVaultWithWebAuthnPrf(credentialId = CREDENTIAL_ID, prfOutput = "cHJm")
        } returns VaultUnlockResult.Success
    }
    private val yubiKeyPasskeyManager: YubiKeyPasskeyManager = mockk()
    private val knownPasskeyManager: KnownPasskeyManager = mockk {
        every { knownPasskeys } returns listOf(KNOWN_YUBIKEY, KNOWN_RETIRED)
        coEvery { refreshNames(any()) } returns Unit.asSuccess()
        every { recordUse(any(), any()) } just runs
    }
    private val device: YubiKeyDevice = mockk()

    @Test
    fun `initial state should reflect whether passkey unlock is available`() {
        every { authRepository.getPasskeyUnlockRequest() } returns null

        assertEquals(
            PasskeyState(isUnlockAvailable = false, isPinRequired = false, dialog = null),
            createViewModel().stateFlow.value,
        )
    }

    @Test
    fun `unlock should list the account's passkeys by name, including ones that can't unlock`() {
        val viewModel = createViewModel()

        viewModel.trySendAction(PasskeyAction.StartClick(PasskeyMode.UNLOCK))

        assertEquals(
            PasskeyState.DialogState.ChoosePasskey(
                mode = PasskeyMode.UNLOCK,
                pin = null,
                assertionRequest = UNLOCK_REQUEST,
                loginToken = null,
                passkeys = listOf(
                    PasskeyItem(
                        credentialId = CREDENTIAL_ID,
                        name = "YubiKey 5C",
                        email = EMAIL,
                        lastUsedEpochMillis = 2L,
                    ),
                    PasskeyItem(
                        credentialId = OTHER_CREDENTIAL_ID,
                        name = null,
                        email = null,
                        lastUsedEpochMillis = null,
                    ),
                    PasskeyItem(
                        credentialId = RETIRED_CREDENTIAL_ID,
                        name = "Lost key",
                        email = EMAIL,
                        lastUsedEpochMillis = 1L,
                        canUnlock = false,
                    ),
                ),
            ),
            viewModel.stateFlow.value.dialog,
        )
        coVerify { knownPasskeyManager.refreshNames(relyingPartyId = "vault.example.com") }
    }

    @Test
    fun `a connected key should make its passkeys choosable and unlock with the chosen one`() {
        coEvery {
            yubiKeyPasskeyManager.findCredentials(
                device = device,
                relyingPartyId = "vault.example.com",
                credentialIds = listOf(CREDENTIAL_ID, OTHER_CREDENTIAL_ID),
            )
        } returns PasskeyProbeResult.Found(
            credentialIds = listOf(CREDENTIAL_ID),
            isPinNeeded = false,
        )
        val narrowedRequest = UNLOCK_REQUEST.copy(allowCredentialIds = listOf(CREDENTIAL_ID))
        coEvery {
            yubiKeyPasskeyManager.getAssertion(device, narrowedRequest, null)
        } returns ASSERTION
        val viewModel = createViewModel()

        viewModel.trySendAction(PasskeyAction.StartClick(PasskeyMode.UNLOCK))
        viewModel.trySendAction(PasskeyAction.YubiKeyConnect(device))

        val dialog = viewModel.stateFlow.value.dialog as PasskeyState.DialogState.ChoosePasskey
        assertEquals(
            listOf(true, false, false),
            dialog.passkeys.map { it.isSelectable },
        )
        coVerify(exactly = 0) { yubiKeyPasskeyManager.getAssertion(any(), any(), any()) }

        viewModel.trySendAction(PasskeyAction.PasskeySelect(OTHER_CREDENTIAL_ID))
        assertEquals(dialog, viewModel.stateFlow.value.dialog)

        viewModel.trySendAction(PasskeyAction.PasskeySelect(CREDENTIAL_ID))

        assertEquals(null, viewModel.stateFlow.value.dialog)
        verify {
            knownPasskeyManager.recordUse(
                credentialId = CREDENTIAL_ID,
                relyingPartyId = "vault.example.com",
            )
        }
    }

    @Test
    fun `a tapped NFC key with one of the passkeys should be used right away`() {
        val nfcDevice: NfcYubiKeyDevice = mockk()
        coEvery {
            yubiKeyPasskeyManager.findCredentials(nfcDevice, any(), any())
        } returns PasskeyProbeResult.Found(
            credentialIds = listOf(CREDENTIAL_ID),
            isPinNeeded = false,
        )
        coEvery {
            yubiKeyPasskeyManager.getAssertion(
                nfcDevice,
                UNLOCK_REQUEST.copy(allowCredentialIds = listOf(CREDENTIAL_ID)),
                null,
            )
        } returns ASSERTION
        val viewModel = createViewModel()

        viewModel.trySendAction(PasskeyAction.StartClick(PasskeyMode.UNLOCK))
        viewModel.trySendAction(PasskeyAction.YubiKeyConnect(nfcDevice))

        assertEquals(null, viewModel.stateFlow.value.dialog)
        coVerify {
            vaultRepository.unlockVaultWithWebAuthnPrf(
                credentialId = CREDENTIAL_ID,
                prfOutput = "cHJm",
            )
        }
    }

    @Test
    fun `a key without any of the account's passkeys should say so and keep listening`() {
        coEvery {
            yubiKeyPasskeyManager.findCredentials(device, any(), any())
        } returns PasskeyProbeResult.Found(credentialIds = emptyList(), isPinNeeded = false)
        val viewModel = createViewModel()

        viewModel.trySendAction(PasskeyAction.StartClick(PasskeyMode.UNLOCK))
        viewModel.trySendAction(PasskeyAction.YubiKeyConnect(device))

        val dialog = viewModel.stateFlow.value.dialog as PasskeyState.DialogState.ChoosePasskey
        assertEquals(
            BitwardenString.this_security_key_has_no_passkey_for_this_account.asText(),
            dialog.message,
        )
        assertEquals(false, dialog.isChecking)
    }

    @Test
    fun `choosing a passkey on a key that needs a PIN should ask for it and then use the key`() {
        coEvery {
            yubiKeyPasskeyManager.findCredentials(device, any(), any())
        } returns PasskeyProbeResult.Found(
            credentialIds = listOf(CREDENTIAL_ID),
            isPinNeeded = true,
        )
        val narrowedRequest = UNLOCK_REQUEST.copy(allowCredentialIds = listOf(CREDENTIAL_ID))
        coEvery {
            yubiKeyPasskeyManager.getAssertion(device, narrowedRequest, "123456")
        } returns ASSERTION.copy(isPinUsed = true)
        val viewModel = createViewModel()

        viewModel.trySendAction(PasskeyAction.StartClick(PasskeyMode.UNLOCK))
        viewModel.trySendAction(PasskeyAction.YubiKeyConnect(device))
        viewModel.trySendAction(PasskeyAction.PasskeySelect(CREDENTIAL_ID))
        assertEquals(
            PasskeyState.DialogState.EnterPin(
                mode = PasskeyMode.UNLOCK,
                pin = "",
                assertionRequest = narrowedRequest,
                loginToken = null,
                device = device,
            ),
            viewModel.stateFlow.value.dialog,
        )
        viewModel.trySendAction(PasskeyAction.PinChange("123456"))
        viewModel.trySendAction(PasskeyAction.PinSubmit)

        assertEquals(
            PasskeyState(isUnlockAvailable = true, isPinRequired = true, dialog = null),
            viewModel.stateFlow.value,
        )

        viewModel.trySendAction(PasskeyAction.StartClick(PasskeyMode.UNLOCK))
        assertEquals(
            PasskeyState.DialogState.EnterPin(mode = PasskeyMode.UNLOCK, pin = ""),
            viewModel.stateFlow.value.dialog,
        )
    }

    @Test
    fun `a key that verifies the user itself should stop asking for the last key's PIN`() {
        coEvery { yubiKeyPasskeyManager.getAssertion(device, any(), "123456") } returns ASSERTION
        val viewModel = createViewModel()
        viewModel.trySendAction(
            PasskeyAction.Internal.AssertionReceive(
                target = AssertionTarget(PasskeyMode.UNLOCK, null, UNLOCK_REQUEST, null),
                device = device,
                result = PasskeyAssertionResult.PinRequired,
            ),
        )
        viewModel.trySendAction(PasskeyAction.PinChange("123456"))
        viewModel.trySendAction(PasskeyAction.PinSubmit)

        assertEquals(false, viewModel.stateFlow.value.isPinRequired)
        viewModel.trySendAction(PasskeyAction.StartClick(PasskeyMode.UNLOCK))
        assertEquals(
            PasskeyState.DialogState.ChoosePasskey::class,
            viewModel.stateFlow.value.dialog!!::class,
        )
    }

    @Test
    fun `login with a key holding several passkeys should let the user choose the account`() {
        val loginRequest = PasskeyLoginRequest(token = "token", assertionRequest = LOGIN_REQUEST)
        coEvery { authRepository.getPasskeyLoginRequest() } returns loginRequest.asSuccess()
        coEvery {
            yubiKeyPasskeyManager.findCredentials(device, "vault.example.com", any())
        } returns PasskeyProbeResult.Found(credentialIds = emptyList(), isPinNeeded = false)
        val workAssertion = ASSERTION.copy(credentialId = "d29yaw", userName = "work@example.com")
        coEvery {
            yubiKeyPasskeyManager.getAssertion(device, LOGIN_REQUEST, null)
        } returns PasskeyAssertionResult.MultipleCredentials(
            assertions = listOf(ASSERTION.copy(userName = EMAIL), workAssertion),
        )
        coEvery {
            authRepository.loginWithPasskey(token = "token", assertion = workAssertion)
        } returns LoginResult.Success
        val viewModel = createViewModel()

        viewModel.trySendAction(PasskeyAction.StartClick(PasskeyMode.LOGIN))
        viewModel.trySendAction(PasskeyAction.YubiKeyConnect(device))

        val dialog = viewModel.stateFlow.value.dialog as PasskeyState.DialogState.ChoosePasskey
        assertEquals(
            listOf("YubiKey 5C" to EMAIL, null to "work@example.com", "Lost key" to EMAIL),
            dialog.passkeys.map { it.name to it.email },
        )
        assertEquals(listOf(true, true, false), dialog.passkeys.map { it.isSelectable })

        viewModel.trySendAction(PasskeyAction.PasskeySelect("d29yaw"))

        assertEquals(null, viewModel.stateFlow.value.dialog)
        coVerify { authRepository.loginWithPasskey(token = "token", assertion = workAssertion) }
        verify {
            knownPasskeyManager.recordUse(
                credentialId = "d29yaw",
                relyingPartyId = "vault.example.com",
            )
        }
    }

    @Test
    fun `a wrong PIN should show the remaining attempts`() {
        coEvery {
            yubiKeyPasskeyManager.getAssertion(device, UNLOCK_REQUEST, "0000")
        } returns PasskeyAssertionResult.InvalidPin(retriesRemaining = 5)
        val viewModel = createViewModel()

        viewModel.trySendAction(
            PasskeyAction.Internal.AssertionReceive(
                target = AssertionTarget(PasskeyMode.UNLOCK, null, UNLOCK_REQUEST, null),
                device = device,
                result = PasskeyAssertionResult.PinRequired,
            ),
        )
        viewModel.trySendAction(PasskeyAction.PinChange("0000"))
        viewModel.trySendAction(PasskeyAction.PinSubmit)

        assertEquals(
            PasskeyState.DialogState.Error(
                BitwardenString.incorrect_pin_x_attempts_remaining.asText(5),
            ),
            viewModel.stateFlow.value.dialog,
        )
    }

    @Test
    fun `an assertion without PRF output should not attempt to unlock`() {
        val viewModel = createViewModel()

        viewModel.trySendAction(
            PasskeyAction.Internal.AssertionReceive(
                target = AssertionTarget(PasskeyMode.UNLOCK, null, UNLOCK_REQUEST, null),
                device = device,
                result = ASSERTION.copy(prfOutput = null),
            ),
        )

        assertEquals(
            PasskeyState.DialogState.Error(
                BitwardenString.this_passkey_cannot_unlock_your_vault.asText(),
            ),
            viewModel.stateFlow.value.dialog,
        )
        coVerify(exactly = 0) { vaultRepository.unlockVaultWithWebAuthnPrf(any(), any()) }
    }

    private fun createViewModel(): PasskeyViewModel = PasskeyViewModel(
        authRepository = authRepository,
        vaultRepository = vaultRepository,
        yubiKeyPasskeyManager = yubiKeyPasskeyManager,
        knownPasskeyManager = knownPasskeyManager,
    )
}

private const val USER_ID = "user"
private const val EMAIL = "me@example.com"
private const val CREDENTIAL_ID = "Y3JlZA"
private const val OTHER_CREDENTIAL_ID = "b3RoZXI"
private const val RETIRED_CREDENTIAL_ID = "b2xk"

private val UNLOCK_REQUEST = PasskeyAssertionRequest(
    challenge = "Y2hhbGxlbmdl",
    relyingPartyId = "vault.example.com",
    origin = "https://vault.example.com",
    allowCredentialIds = listOf(CREDENTIAL_ID, OTHER_CREDENTIAL_ID),
    prfSalt = "c2FsdA",
    timeoutMillis = null,
)

private val LOGIN_REQUEST = UNLOCK_REQUEST.copy(allowCredentialIds = emptyList())

private val KNOWN_YUBIKEY = KnownPasskeyJson(
    credentialId = CREDENTIAL_ID,
    relyingPartyId = "vault.example.com",
    userId = USER_ID,
    email = EMAIL,
    name = "YubiKey 5C",
    lastUsedEpochMillis = 2L,
)

private val KNOWN_RETIRED = KNOWN_YUBIKEY.copy(
    credentialId = RETIRED_CREDENTIAL_ID,
    name = "Lost key",
    lastUsedEpochMillis = 1L,
)

private val ASSERTION = PasskeyAssertionResult.Success(
    credentialId = CREDENTIAL_ID,
    deviceResponse = "{}",
    prfOutput = "cHJm",
)
