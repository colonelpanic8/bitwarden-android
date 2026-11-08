package com.x8bit.bitwarden.ui.auth.feature.passkey

import androidx.lifecycle.viewModelScope
import com.bitwarden.ui.platform.base.BaseViewModel
import com.bitwarden.ui.platform.resource.BitwardenString
import com.bitwarden.ui.util.Text
import com.bitwarden.ui.util.asText
import com.x8bit.bitwarden.data.auth.manager.YubiKeyPasskeyManager
import com.x8bit.bitwarden.data.auth.manager.model.PasskeyAssertionRequest
import com.x8bit.bitwarden.data.auth.manager.model.PasskeyAssertionResult
import com.x8bit.bitwarden.data.auth.repository.AuthRepository
import com.x8bit.bitwarden.data.auth.repository.model.LoginResult
import com.x8bit.bitwarden.data.auth.repository.model.PasskeyLoginRequest
import com.x8bit.bitwarden.data.vault.repository.VaultRepository
import com.x8bit.bitwarden.data.vault.repository.model.VaultUnlockResult
import com.yubico.yubikit.core.YubiKeyDevice
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Handles logging in and unlocking the vault with a passkey on a YubiKey.
 */
@Suppress("TooManyFunctions")
@HiltViewModel
class PasskeyViewModel @Inject constructor(
    private val authRepository: AuthRepository,
    private val vaultRepository: VaultRepository,
    private val yubiKeyPasskeyManager: YubiKeyPasskeyManager,
) : BaseViewModel<PasskeyState, Unit, PasskeyAction>(
    initialState = PasskeyState(
        isUnlockAvailable = authRepository.getPasskeyUnlockRequest() != null,
        isPinRequired = false,
        dialog = null,
    ),
) {
    init {
        authRepository
            .userStateFlow
            .map {
                PasskeyAction.Internal.UnlockAvailabilityUpdate(
                    isAvailable = authRepository.getPasskeyUnlockRequest() != null,
                )
            }
            .onEach(::sendAction)
            .launchIn(viewModelScope)
    }

    override fun handleAction(action: PasskeyAction) {
        when (action) {
            is PasskeyAction.StartClick -> handleStartClick(action)
            is PasskeyAction.PinChange -> handlePinChange(action)
            PasskeyAction.PinSubmit -> handlePinSubmit()
            is PasskeyAction.YubiKeyConnect -> handleYubiKeyConnect(action)
            PasskeyAction.DialogDismiss -> mutableStateFlow.value = state.copy(dialog = null)
            is PasskeyAction.Internal -> handleInternalAction(action)
        }
    }

    private fun handleInternalAction(action: PasskeyAction.Internal) {
        when (action) {
            is PasskeyAction.Internal.UnlockAvailabilityUpdate -> {
                mutableStateFlow.value = state.copy(isUnlockAvailable = action.isAvailable)
            }

            is PasskeyAction.Internal.LoginRequestReceive -> handleLoginRequestReceive(action)
            is PasskeyAction.Internal.AssertionReceive -> handleAssertionReceive(action)
            is PasskeyAction.Internal.LoginResultReceive -> handleLoginResultReceive(action)
            is PasskeyAction.Internal.UnlockResultReceive -> handleUnlockResultReceive(action)
        }
    }

    private fun handleStartClick(action: PasskeyAction.StartClick) {
        if (state.isPinRequired) {
            mutableStateFlow.value = state.copy(
                dialog = PasskeyState.DialogState.EnterPin(mode = action.mode, pin = ""),
            )
        } else {
            beginWaitingForKey(mode = action.mode, pin = null)
        }
    }

    private fun handlePinChange(action: PasskeyAction.PinChange) {
        val dialog = state.dialog as? PasskeyState.DialogState.EnterPin ?: return
        mutableStateFlow.value = state.copy(dialog = dialog.copy(pin = action.pin))
    }

    private fun handlePinSubmit() {
        val dialog = state.dialog as? PasskeyState.DialogState.EnterPin ?: return
        if (dialog.pin.isEmpty()) return
        val assertionRequest = dialog.assertionRequest
        if (assertionRequest != null) {
            mutableStateFlow.value = state.copy(
                dialog = PasskeyState.DialogState.WaitingForKey(
                    mode = dialog.mode,
                    pin = dialog.pin,
                    assertionRequest = assertionRequest,
                    loginToken = dialog.loginToken,
                ),
            )
        } else {
            beginWaitingForKey(mode = dialog.mode, pin = dialog.pin)
        }
    }

    private fun beginWaitingForKey(mode: PasskeyMode, pin: String?) {
        when (mode) {
            PasskeyMode.LOGIN -> {
                mutableStateFlow.value = state.copy(
                    dialog = PasskeyState.DialogState.Loading(BitwardenString.loading.asText()),
                )
                viewModelScope.launch {
                    sendAction(
                        PasskeyAction.Internal.LoginRequestReceive(
                            pin = pin,
                            result = authRepository.getPasskeyLoginRequest(),
                        ),
                    )
                }
            }

            PasskeyMode.UNLOCK -> {
                val request = authRepository.getPasskeyUnlockRequest()
                mutableStateFlow.value = state.copy(
                    dialog = request
                        ?.let {
                            PasskeyState.DialogState.WaitingForKey(
                                mode = PasskeyMode.UNLOCK,
                                pin = pin,
                                assertionRequest = it,
                                loginToken = null,
                            )
                        }
                        ?: PasskeyState.DialogState.Error(
                            BitwardenString.this_passkey_cannot_unlock_your_vault.asText(),
                        ),
                )
            }
        }
    }

    private fun handleLoginRequestReceive(action: PasskeyAction.Internal.LoginRequestReceive) {
        if (state.dialog !is PasskeyState.DialogState.Loading) return
        mutableStateFlow.value = state.copy(
            dialog = action.result.fold(
                onSuccess = { request ->
                    PasskeyState.DialogState.WaitingForKey(
                        mode = PasskeyMode.LOGIN,
                        pin = action.pin,
                        assertionRequest = request.assertionRequest,
                        loginToken = request.token,
                    )
                },
                onFailure = { error ->
                    PasskeyState.DialogState.Error(error.toErrorText())
                },
            ),
        )
    }

    private fun handleYubiKeyConnect(action: PasskeyAction.YubiKeyConnect) {
        val dialog = state.dialog as? PasskeyState.DialogState.WaitingForKey ?: return
        mutableStateFlow.value = state.copy(
            dialog = PasskeyState.DialogState.Loading(BitwardenString.verifying_passkey.asText()),
        )
        viewModelScope.launch {
            val result = yubiKeyPasskeyManager.getAssertion(
                device = action.device,
                request = dialog.assertionRequest,
                pin = dialog.pin,
            )
            sendAction(PasskeyAction.Internal.AssertionReceive(dialog = dialog, result = result))
        }
    }

    private fun handleAssertionReceive(action: PasskeyAction.Internal.AssertionReceive) {
        when (val result = action.result) {
            is PasskeyAssertionResult.Success -> handleAssertionSuccess(action.dialog, result)

            PasskeyAssertionResult.PinRequired -> {
                mutableStateFlow.value = state.copy(
                    isPinRequired = true,
                    dialog = PasskeyState.DialogState.EnterPin(
                        mode = action.dialog.mode,
                        pin = "",
                        assertionRequest = action.dialog.assertionRequest,
                        loginToken = action.dialog.loginToken,
                    ),
                )
            }

            PasskeyAssertionResult.UserVerificationUnavailable -> showError(
                BitwardenString.this_security_key_cannot_verify_you.asText(),
            )

            is PasskeyAssertionResult.InvalidPin -> showError(
                BitwardenString.incorrect_pin_x_attempts_remaining.asText(
                    result.retriesRemaining,
                ),
            )

            PasskeyAssertionResult.NoCredentials -> showError(
                BitwardenString.this_security_key_has_no_passkey_for_this_account.asText(),
            )

            is PasskeyAssertionResult.Error -> showError(
                BitwardenString.the_security_key_could_not_be_read_hold_it_in_place_and_try_again
                    .asText(),
            )
        }
    }

    private fun handleAssertionSuccess(
        dialog: PasskeyState.DialogState.WaitingForKey,
        result: PasskeyAssertionResult.Success,
    ) {
        val token = dialog.loginToken
        val prfOutput = result.prfOutput
        when {
            dialog.mode == PasskeyMode.LOGIN && token != null -> {
                viewModelScope.launch {
                    sendAction(
                        PasskeyAction.Internal.LoginResultReceive(
                            authRepository.loginWithPasskey(
                                token = token,
                                assertion = result,
                            ),
                        ),
                    )
                }
            }

            prfOutput != null -> {
                viewModelScope.launch {
                    sendAction(
                        PasskeyAction.Internal.UnlockResultReceive(
                            vaultRepository.unlockVaultWithWebAuthnPrf(
                                credentialId = result.credentialId,
                                prfOutput = prfOutput,
                            ),
                        ),
                    )
                }
            }

            else -> showError(
                BitwardenString.this_passkey_cannot_unlock_your_vault.asText(),
            )
        }
    }

    private fun handleLoginResultReceive(action: PasskeyAction.Internal.LoginResultReceive) {
        when (val result = action.result) {
            LoginResult.Success -> mutableStateFlow.value = state.copy(dialog = null)
            is LoginResult.Error -> showError(
                result.errorMessage?.asText() ?: BitwardenString.generic_error_message.asText(),
            )

            is LoginResult.NewDeviceVerification -> showError(
                result.errorMessage?.asText() ?: BitwardenString.generic_error_message.asText(),
            )

            LoginResult.CertificateError,
            is LoginResult.ConfirmKeyConnectorDomain,
            LoginResult.EncryptionKeyMigrationRequired,
            LoginResult.TwoFactorRequired,
            LoginResult.UnofficialServerError,
                -> showError(BitwardenString.generic_error_message.asText())
        }
    }

    private fun handleUnlockResultReceive(action: PasskeyAction.Internal.UnlockResultReceive) {
        when (action.result) {
            VaultUnlockResult.Success -> mutableStateFlow.value = state.copy(dialog = null)
            is VaultUnlockResult.AuthenticationError -> showError(
                BitwardenString.this_passkey_cannot_unlock_your_vault.asText(),
            )

            else -> showError(BitwardenString.generic_error_message.asText())
        }
    }

    private fun showError(message: Text) {
        mutableStateFlow.value = state.copy(dialog = PasskeyState.DialogState.Error(message))
    }

    private fun Throwable.toErrorText(): Text =
        message?.asText() ?: BitwardenString.generic_error_message.asText()
}

/**
 * Whether the passkey flow logs in or unlocks the vault.
 */
enum class PasskeyMode {
    LOGIN,
    UNLOCK,
}

/**
 * Models the state of the passkey login and unlock flow.
 *
 * @property isUnlockAvailable Whether the active account has a passkey that can unlock the vault.
 * @property isPinRequired Whether the last security key needed a PIN, so it is asked for before
 * the key is tapped next time.
 * @property dialog The dialog to display, if any.
 */
data class PasskeyState(
    val isUnlockAvailable: Boolean,
    val isPinRequired: Boolean,
    val dialog: DialogState?,
) {
    /**
     * The dialogs of the passkey flow.
     */
    sealed class DialogState {
        /**
         * Asks for the security key's PIN, keeping the pending [assertionRequest] if the key was
         * already tapped.
         */
        data class EnterPin(
            val mode: PasskeyMode,
            val pin: String,
            val assertionRequest: PasskeyAssertionRequest? = null,
            val loginToken: String? = null,
        ) : DialogState()

        /**
         * Waits for the security key to be tapped or inserted.
         */
        data class WaitingForKey(
            val mode: PasskeyMode,
            val pin: String?,
            val assertionRequest: PasskeyAssertionRequest,
            val loginToken: String?,
        ) : DialogState()

        /**
         * Shows progress.
         */
        data class Loading(val message: Text) : DialogState()

        /**
         * Shows an error.
         */
        data class Error(val message: Text) : DialogState()
    }
}

/**
 * Models actions for the passkey login and unlock flow.
 */
sealed class PasskeyAction {
    /**
     * The user started logging in or unlocking with a passkey.
     */
    data class StartClick(val mode: PasskeyMode) : PasskeyAction()

    /**
     * The PIN input changed.
     */
    data class PinChange(val pin: String) : PasskeyAction()

    /**
     * The user submitted the PIN.
     */
    data object PinSubmit : PasskeyAction()

    /**
     * A YubiKey was tapped or inserted.
     */
    data class YubiKeyConnect(val device: YubiKeyDevice) : PasskeyAction()

    /**
     * The dialog was dismissed.
     */
    data object DialogDismiss : PasskeyAction()

    /**
     * Actions for internal use by the ViewModel.
     */
    sealed class Internal : PasskeyAction() {
        /**
         * Whether the active account can be unlocked with a passkey changed.
         */
        data class UnlockAvailabilityUpdate(val isAvailable: Boolean) : Internal()

        /**
         * A passkey login challenge was received.
         */
        data class LoginRequestReceive(
            val pin: String?,
            val result: Result<PasskeyLoginRequest>,
        ) : Internal()

        /**
         * The security key returned a result.
         */
        data class AssertionReceive(
            val dialog: PasskeyState.DialogState.WaitingForKey,
            val result: PasskeyAssertionResult,
        ) : Internal()

        /**
         * The passkey login completed.
         */
        data class LoginResultReceive(val result: LoginResult) : Internal()

        /**
         * The passkey unlock completed.
         */
        data class UnlockResultReceive(val result: VaultUnlockResult) : Internal()
    }
}
