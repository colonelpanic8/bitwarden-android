package com.x8bit.bitwarden.ui.auth.feature.passkey

import com.bitwarden.core.data.util.asSuccess
import com.bitwarden.ui.platform.base.BaseViewModelTest
import com.bitwarden.ui.platform.resource.BitwardenString
import com.bitwarden.ui.util.asText
import com.x8bit.bitwarden.data.auth.manager.YubiKeyPasskeyManager
import com.x8bit.bitwarden.data.auth.manager.model.PasskeyAssertionRequest
import com.x8bit.bitwarden.data.auth.manager.model.PasskeyAssertionResult
import com.x8bit.bitwarden.data.auth.repository.AuthRepository
import com.x8bit.bitwarden.data.auth.repository.model.LoginResult
import com.x8bit.bitwarden.data.auth.repository.model.PasskeyLoginRequest
import com.x8bit.bitwarden.data.auth.repository.model.UserState
import com.x8bit.bitwarden.data.vault.repository.VaultRepository
import com.x8bit.bitwarden.data.vault.repository.model.VaultUnlockResult
import com.yubico.yubikit.core.YubiKeyDevice
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class PasskeyViewModelTest : BaseViewModelTest() {

    private val mutableUserStateFlow = MutableStateFlow<UserState?>(null)
    private val authRepository: AuthRepository = mockk {
        every { userStateFlow } returns mutableUserStateFlow
        every { getPasskeyUnlockRequest() } returns UNLOCK_REQUEST
    }
    private val vaultRepository: VaultRepository = mockk()
    private val yubiKeyPasskeyManager: YubiKeyPasskeyManager = mockk()
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
    fun `unlock with a key that verifies the user itself should not ask for a PIN`() {
        coEvery {
            yubiKeyPasskeyManager.getAssertion(device, UNLOCK_REQUEST, null)
        } returns ASSERTION
        coEvery {
            vaultRepository.unlockVaultWithWebAuthnPrf(credentialId = "Y3JlZA", prfOutput = "cHJm")
        } returns VaultUnlockResult.Success
        val viewModel = createViewModel()

        viewModel.trySendAction(PasskeyAction.StartClick(PasskeyMode.UNLOCK))
        assertEquals(
            PasskeyState.DialogState.WaitingForKey(
                mode = PasskeyMode.UNLOCK,
                pin = null,
                assertionRequest = UNLOCK_REQUEST,
                loginToken = null,
            ),
            viewModel.stateFlow.value.dialog,
        )
        viewModel.trySendAction(PasskeyAction.YubiKeyConnect(device))

        assertEquals(null, viewModel.stateFlow.value.dialog)
        coVerify {
            vaultRepository.unlockVaultWithWebAuthnPrf(credentialId = "Y3JlZA", prfOutput = "cHJm")
        }
    }

    @Test
    fun `a key that needs a PIN should ask for it and then for the PIN first next time`() {
        coEvery {
            yubiKeyPasskeyManager.getAssertion(device, UNLOCK_REQUEST, null)
        } returns PasskeyAssertionResult.PinRequired
        coEvery {
            yubiKeyPasskeyManager.getAssertion(device, UNLOCK_REQUEST, "123456")
        } returns ASSERTION
        coEvery {
            vaultRepository.unlockVaultWithWebAuthnPrf(credentialId = "Y3JlZA", prfOutput = "cHJm")
        } returns VaultUnlockResult.Success
        val viewModel = createViewModel()

        viewModel.trySendAction(PasskeyAction.StartClick(PasskeyMode.UNLOCK))
        viewModel.trySendAction(PasskeyAction.YubiKeyConnect(device))
        assertEquals(
            PasskeyState(
                isUnlockAvailable = true,
                isPinRequired = true,
                dialog = PasskeyState.DialogState.EnterPin(
                    mode = PasskeyMode.UNLOCK,
                    pin = "",
                    assertionRequest = UNLOCK_REQUEST,
                    loginToken = null,
                ),
            ),
            viewModel.stateFlow.value,
        )
        viewModel.trySendAction(PasskeyAction.PinChange("123456"))
        viewModel.trySendAction(PasskeyAction.PinSubmit)
        viewModel.trySendAction(PasskeyAction.YubiKeyConnect(device))

        assertEquals(null, viewModel.stateFlow.value.dialog)
        coVerify {
            vaultRepository.unlockVaultWithWebAuthnPrf(credentialId = "Y3JlZA", prfOutput = "cHJm")
        }

        viewModel.trySendAction(PasskeyAction.StartClick(PasskeyMode.UNLOCK))
        assertEquals(
            PasskeyState.DialogState.EnterPin(mode = PasskeyMode.UNLOCK, pin = ""),
            viewModel.stateFlow.value.dialog,
        )
    }

    @Test
    fun `login flow should log in with the assertion for the fetched challenge`() {
        val loginRequest = PasskeyLoginRequest(token = "token", assertionRequest = UNLOCK_REQUEST)
        coEvery { authRepository.getPasskeyLoginRequest() } returns loginRequest.asSuccess()
        coEvery {
            yubiKeyPasskeyManager.getAssertion(device, UNLOCK_REQUEST, null)
        } returns ASSERTION
        coEvery {
            authRepository.loginWithPasskey(token = "token", assertion = ASSERTION)
        } returns LoginResult.Success
        val viewModel = createViewModel()

        viewModel.trySendAction(PasskeyAction.StartClick(PasskeyMode.LOGIN))
        viewModel.trySendAction(PasskeyAction.YubiKeyConnect(device))

        assertEquals(null, viewModel.stateFlow.value.dialog)
        coVerify { authRepository.loginWithPasskey(token = "token", assertion = ASSERTION) }
    }

    @Test
    fun `a wrong PIN should show the remaining attempts`() {
        coEvery {
            yubiKeyPasskeyManager.getAssertion(device, UNLOCK_REQUEST, null)
        } returns PasskeyAssertionResult.PinRequired
        coEvery {
            yubiKeyPasskeyManager.getAssertion(device, UNLOCK_REQUEST, "0000")
        } returns PasskeyAssertionResult.InvalidPin(retriesRemaining = 5)
        val viewModel = createViewModel()

        viewModel.trySendAction(PasskeyAction.StartClick(PasskeyMode.UNLOCK))
        viewModel.trySendAction(PasskeyAction.YubiKeyConnect(device))
        viewModel.trySendAction(PasskeyAction.PinChange("0000"))
        viewModel.trySendAction(PasskeyAction.PinSubmit)
        viewModel.trySendAction(PasskeyAction.YubiKeyConnect(device))

        assertEquals(
            PasskeyState.DialogState.Error(
                BitwardenString.incorrect_pin_x_attempts_remaining.asText(5),
            ),
            viewModel.stateFlow.value.dialog,
        )
    }

    @Test
    fun `an assertion without PRF output should not attempt to unlock`() {
        coEvery {
            yubiKeyPasskeyManager.getAssertion(device, UNLOCK_REQUEST, null)
        } returns ASSERTION.copy(prfOutput = null)
        val viewModel = createViewModel()

        viewModel.trySendAction(PasskeyAction.StartClick(PasskeyMode.UNLOCK))
        viewModel.trySendAction(PasskeyAction.YubiKeyConnect(device))

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
    )
}

private val UNLOCK_REQUEST = PasskeyAssertionRequest(
    challenge = "Y2hhbGxlbmdl",
    relyingPartyId = "vault.example.com",
    origin = "https://vault.example.com",
    allowCredentialIds = listOf("Y3JlZA"),
    prfSalt = "c2FsdA",
    timeoutMillis = null,
)

private val ASSERTION = PasskeyAssertionResult.Success(
    credentialId = "Y3JlZA",
    deviceResponse = "{}",
    prfOutput = "cHJm",
)
