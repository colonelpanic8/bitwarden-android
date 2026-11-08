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
            PasskeyState(isUnlockAvailable = false, dialog = null),
            createViewModel().stateFlow.value,
        )
    }

    @Test
    fun `unlock flow should unlock the vault with the PRF output of the tapped key`() {
        coEvery {
            yubiKeyPasskeyManager.getAssertion(device, UNLOCK_REQUEST, "123456")
        } returns ASSERTION
        coEvery {
            vaultRepository.unlockVaultWithWebAuthnPrf(credentialId = "Y3JlZA", prfOutput = "cHJm")
        } returns VaultUnlockResult.Success
        val viewModel = createViewModel()

        viewModel.trySendAction(PasskeyAction.StartClick(PasskeyMode.UNLOCK))
        viewModel.trySendAction(PasskeyAction.PinChange("123456"))
        viewModel.trySendAction(PasskeyAction.PinSubmit)
        assertEquals(
            PasskeyState.DialogState.WaitingForKey(
                mode = PasskeyMode.UNLOCK,
                pin = "123456",
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
    fun `login flow should log in with the assertion for the fetched challenge`() {
        val loginRequest = PasskeyLoginRequest(token = "token", assertionRequest = UNLOCK_REQUEST)
        coEvery { authRepository.getPasskeyLoginRequest() } returns loginRequest.asSuccess()
        coEvery {
            yubiKeyPasskeyManager.getAssertion(device, UNLOCK_REQUEST, "123456")
        } returns ASSERTION
        coEvery {
            authRepository.loginWithPasskey(token = "token", assertion = ASSERTION)
        } returns LoginResult.Success
        val viewModel = createViewModel()

        viewModel.trySendAction(PasskeyAction.StartClick(PasskeyMode.LOGIN))
        viewModel.trySendAction(PasskeyAction.PinChange("123456"))
        viewModel.trySendAction(PasskeyAction.PinSubmit)
        viewModel.trySendAction(PasskeyAction.YubiKeyConnect(device))

        assertEquals(null, viewModel.stateFlow.value.dialog)
        coVerify { authRepository.loginWithPasskey(token = "token", assertion = ASSERTION) }
    }

    @Test
    fun `a wrong PIN should show the remaining attempts`() {
        coEvery {
            yubiKeyPasskeyManager.getAssertion(device, UNLOCK_REQUEST, "0000")
        } returns PasskeyAssertionResult.InvalidPin(retriesRemaining = 5)
        val viewModel = createViewModel()

        viewModel.trySendAction(PasskeyAction.StartClick(PasskeyMode.UNLOCK))
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
            yubiKeyPasskeyManager.getAssertion(device, UNLOCK_REQUEST, "123456")
        } returns ASSERTION.copy(prfOutput = null)
        val viewModel = createViewModel()

        viewModel.trySendAction(PasskeyAction.StartClick(PasskeyMode.UNLOCK))
        viewModel.trySendAction(PasskeyAction.PinChange("123456"))
        viewModel.trySendAction(PasskeyAction.PinSubmit)
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
