package com.x8bit.bitwarden.ui.auth.feature.passkey

import androidx.lifecycle.viewModelScope
import com.bitwarden.ui.platform.base.BaseViewModel
import com.bitwarden.ui.platform.resource.BitwardenString
import com.bitwarden.ui.util.Text
import com.bitwarden.ui.util.asText
import com.x8bit.bitwarden.data.auth.manager.KnownPasskeyManager
import com.x8bit.bitwarden.data.auth.manager.YubiKeyPasskeyManager
import com.x8bit.bitwarden.data.auth.manager.model.PasskeyAssertionRequest
import com.x8bit.bitwarden.data.auth.manager.model.PasskeyAssertionResult
import com.x8bit.bitwarden.data.auth.manager.model.PasskeyProbeResult
import com.x8bit.bitwarden.data.auth.manager.toCanonicalCredentialId
import com.x8bit.bitwarden.data.auth.repository.AuthRepository
import com.x8bit.bitwarden.data.auth.repository.model.LoginResult
import com.x8bit.bitwarden.data.auth.repository.model.PasskeyLoginRequest
import com.x8bit.bitwarden.data.vault.repository.VaultRepository
import com.x8bit.bitwarden.data.vault.repository.model.VaultUnlockResult
import com.yubico.yubikit.android.transport.nfc.NfcYubiKeyDevice
import com.yubico.yubikit.core.YubiKeyDevice
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Handles logging in and unlocking the vault with a passkey on a security key. The user picks
 * from the passkeys they have used, and only those on a connected key can be picked.
 */
@Suppress("TooManyFunctions")
@HiltViewModel
class PasskeyViewModel @Inject constructor(
    private val authRepository: AuthRepository,
    private val vaultRepository: VaultRepository,
    private val yubiKeyPasskeyManager: YubiKeyPasskeyManager,
    private val knownPasskeyManager: KnownPasskeyManager,
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
            is PasskeyAction.PasskeySelect -> handlePasskeySelect(action)
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
            PasskeyAction.Internal.KnownPasskeysUpdate -> handleKnownPasskeysUpdate()
            is PasskeyAction.Internal.ProbeReceive -> handleProbeReceive(action)
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
            beginChoosing(mode = action.mode, pin = null)
        }
    }

    private fun handlePinChange(action: PasskeyAction.PinChange) {
        val dialog = state.dialog as? PasskeyState.DialogState.EnterPin ?: return
        mutableStateFlow.value = state.copy(dialog = dialog.copy(pin = action.pin))
    }

    private fun handlePinSubmit() {
        val dialog = state.dialog as? PasskeyState.DialogState.EnterPin ?: return
        if (dialog.pin.isEmpty()) return
        val target = dialog.assertionRequest?.let {
            AssertionTarget(
                mode = dialog.mode,
                pin = dialog.pin,
                assertionRequest = it,
                loginToken = dialog.loginToken,
            )
        }
        when {
            target == null -> beginChoosing(mode = dialog.mode, pin = dialog.pin)
            dialog.device != null -> getAssertion(device = dialog.device, target = target)
            else -> waitForKey(target)
        }
    }

    private fun beginChoosing(mode: PasskeyMode, pin: String?) {
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
                if (request == null) {
                    showError(BitwardenString.this_passkey_cannot_unlock_your_vault.asText())
                    return
                }
                mutableStateFlow.value = state.copy(
                    dialog = PasskeyState.DialogState.ChoosePasskey(
                        mode = PasskeyMode.UNLOCK,
                        pin = pin,
                        assertionRequest = request,
                        loginToken = null,
                        passkeys = unlockPasskeys(request = request, previous = emptyList()),
                    ),
                )
                viewModelScope.launch {
                    knownPasskeyManager.refreshNames(relyingPartyId = request.relyingPartyId)
                    sendAction(PasskeyAction.Internal.KnownPasskeysUpdate)
                }
            }
        }
    }

    private fun handleLoginRequestReceive(action: PasskeyAction.Internal.LoginRequestReceive) {
        if (state.dialog !is PasskeyState.DialogState.Loading) return
        mutableStateFlow.value = state.copy(
            dialog = action.result.fold(
                onSuccess = { request ->
                    PasskeyState.DialogState.ChoosePasskey(
                        mode = PasskeyMode.LOGIN,
                        pin = action.pin,
                        assertionRequest = request.assertionRequest,
                        loginToken = request.token,
                        passkeys = loginPasskeys(request = request.assertionRequest),
                    )
                },
                onFailure = { error ->
                    PasskeyState.DialogState.Error(error.toErrorText())
                },
            ),
        )
    }

    private fun handleKnownPasskeysUpdate() {
        val dialog = state.dialog as? PasskeyState.DialogState.ChoosePasskey ?: return
        if (dialog.mode != PasskeyMode.UNLOCK) return
        mutableStateFlow.value = state.copy(
            dialog = dialog.copy(
                passkeys = unlockPasskeys(
                    request = dialog.assertionRequest,
                    previous = dialog.passkeys,
                ),
            ),
        )
    }

    private fun handleYubiKeyConnect(action: PasskeyAction.YubiKeyConnect) {
        when (val dialog = state.dialog) {
            is PasskeyState.DialogState.WaitingForKey -> {
                getAssertion(device = action.device, target = dialog.target)
            }

            is PasskeyState.DialogState.ChoosePasskey -> {
                mutableStateFlow.value = state.copy(
                    dialog = dialog.copy(isChecking = true, message = null),
                )
                val candidates = dialog.passkeys.filter { it.canUnlock }.map { it.credentialId }
                viewModelScope.launch {
                    val result = if (candidates.isEmpty()) {
                        PasskeyProbeResult.Found(credentialIds = emptyList(), isPinNeeded = false)
                    } else {
                        yubiKeyPasskeyManager.findCredentials(
                            device = action.device,
                            relyingPartyId = dialog.assertionRequest.relyingPartyId,
                            credentialIds = candidates,
                        )
                    }
                    sendAction(PasskeyAction.Internal.ProbeReceive(action.device, result))
                }
            }

            else -> Unit
        }
    }

    private fun handleProbeReceive(action: PasskeyAction.Internal.ProbeReceive) {
        val dialog = state.dialog as? PasskeyState.DialogState.ChoosePasskey ?: return
        val result = action.result
        if (result is PasskeyProbeResult.Error) {
            mutableStateFlow.value = state.copy(
                dialog = dialog.copy(
                    isChecking = false,
                    message = BitwardenString
                        .the_security_key_could_not_be_read_hold_it_in_place_and_try_again
                        .asText(),
                ),
            )
            return
        }
        val found = result as PasskeyProbeResult.Found
        val matches = found.credentialIds?.map { it.toCanonicalCredentialId() }?.toSet()
        when {
            // The key can't be checked, or may hold passkeys this device hasn't seen: ask it
            // with the full request.
            matches == null || (matches.isEmpty() && dialog.mode == PasskeyMode.LOGIN) -> {
                getAssertion(device = action.device, target = dialog.target)
            }

            matches.isEmpty() -> {
                mutableStateFlow.value = state.copy(
                    dialog = dialog.copy(
                        isChecking = false,
                        message = BitwardenString
                            .this_security_key_has_no_passkey_for_this_account
                            .asText(),
                    ),
                )
            }

            else -> showPasskeysOnKey(
                dialog = dialog,
                device = action.device,
                matches = matches,
                isPinNeeded = found.isPinNeeded,
            )
        }
    }

    private fun showPasskeysOnKey(
        dialog: PasskeyState.DialogState.ChoosePasskey,
        device: YubiKeyDevice,
        matches: Set<String>,
        isPinNeeded: Boolean,
    ) {
        val updated = dialog.copy(
            isChecking = false,
            passkeys = dialog.passkeys
                .map { passkey ->
                    if (passkey.credentialId in matches) {
                        passkey.copy(
                            device = device,
                            isPinNeeded = isPinNeeded,
                            assertion = null,
                        )
                    } else {
                        passkey
                    }
                }
                .sortedForDisplay(),
        )
        val onThisKey = updated.passkeys.filter {
            it.isSelectable && it.device === device
        }
        // A tapped NFC key is gone once the user looks at the list, so use it right away
        // when there's nothing to choose.
        if (onThisKey.size == 1 && !device.isPersistent) {
            selectPasskey(
                dialog = updated,
                passkey = onThisKey.single(),
                isKeyPresent = true,
            )
        } else {
            mutableStateFlow.value = state.copy(dialog = updated)
        }
    }

    private fun handlePasskeySelect(action: PasskeyAction.PasskeySelect) {
        val dialog = state.dialog as? PasskeyState.DialogState.ChoosePasskey ?: return
        val passkey = dialog
            .passkeys
            .firstOrNull { it.credentialId == action.credentialId && it.isSelectable }
            ?: return
        selectPasskey(
            dialog = dialog,
            passkey = passkey,
            isKeyPresent = passkey.device?.isPersistent == true,
        )
    }

    private fun selectPasskey(
        dialog: PasskeyState.DialogState.ChoosePasskey,
        passkey: PasskeyItem,
        isKeyPresent: Boolean,
    ) {
        val assertion = passkey.assertion
        if (assertion != null) {
            mutableStateFlow.value = state.copy(
                dialog = PasskeyState.DialogState.Loading(
                    BitwardenString.verifying_passkey.asText(),
                ),
            )
            handleAssertionSuccess(target = dialog.target, result = assertion)
            return
        }
        val device = passkey.device ?: return
        val target = dialog.target.copy(
            assertionRequest = dialog.assertionRequest.copy(
                allowCredentialIds = listOf(passkey.credentialId),
            ),
        )
        when {
            passkey.isPinNeeded && target.pin == null -> {
                mutableStateFlow.value = state.copy(
                    dialog = PasskeyState.DialogState.EnterPin(
                        mode = target.mode,
                        pin = "",
                        assertionRequest = target.assertionRequest,
                        loginToken = target.loginToken,
                        device = device.takeIf { it.isPersistent },
                    ),
                )
            }

            isKeyPresent -> getAssertion(device = device, target = target)
            else -> waitForKey(target)
        }
    }

    private fun waitForKey(target: AssertionTarget) {
        mutableStateFlow.value = state.copy(
            dialog = PasskeyState.DialogState.WaitingForKey(
                mode = target.mode,
                pin = target.pin,
                assertionRequest = target.assertionRequest,
                loginToken = target.loginToken,
            ),
        )
    }

    private fun getAssertion(device: YubiKeyDevice, target: AssertionTarget) {
        mutableStateFlow.value = state.copy(
            dialog = PasskeyState.DialogState.Loading(BitwardenString.verifying_passkey.asText()),
        )
        viewModelScope.launch {
            val result = yubiKeyPasskeyManager.getAssertion(
                device = device,
                request = target.assertionRequest,
                pin = target.pin,
            )
            sendAction(
                PasskeyAction.Internal.AssertionReceive(
                    target = target,
                    device = device,
                    result = result,
                ),
            )
        }
    }

    private fun handleAssertionReceive(action: PasskeyAction.Internal.AssertionReceive) {
        val target = action.target
        when (val result = action.result) {
            is PasskeyAssertionResult.Success -> {
                mutableStateFlow.value = state.copy(isPinRequired = result.isPinUsed)
                handleAssertionSuccess(target = target, result = result)
            }

            is PasskeyAssertionResult.MultipleCredentials -> {
                mutableStateFlow.value = state.copy(
                    isPinRequired = result.assertions.any { it.isPinUsed },
                    dialog = PasskeyState.DialogState.ChoosePasskey(
                        mode = target.mode,
                        pin = target.pin,
                        assertionRequest = target.assertionRequest,
                        loginToken = target.loginToken,
                        passkeys = loginPasskeys(
                            request = target.assertionRequest,
                            device = action.device,
                            assertions = result.assertions,
                        ),
                    ),
                )
            }

            PasskeyAssertionResult.PinRequired -> {
                mutableStateFlow.value = state.copy(
                    isPinRequired = true,
                    dialog = PasskeyState.DialogState.EnterPin(
                        mode = target.mode,
                        pin = "",
                        assertionRequest = target.assertionRequest,
                        loginToken = target.loginToken,
                        device = action.device.takeIf { it.isPersistent },
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
        target: AssertionTarget,
        result: PasskeyAssertionResult.Success,
    ) {
        val token = target.loginToken
        val prfOutput = result.prfOutput
        val relyingPartyId = target.assertionRequest.relyingPartyId
        when {
            target.mode == PasskeyMode.LOGIN && token != null -> {
                viewModelScope.launch {
                    sendAction(
                        PasskeyAction.Internal.LoginResultReceive(
                            result = authRepository.loginWithPasskey(
                                token = token,
                                assertion = result,
                            ),
                            credentialId = result.credentialId,
                            relyingPartyId = relyingPartyId,
                        ),
                    )
                }
            }

            prfOutput != null -> {
                viewModelScope.launch {
                    sendAction(
                        PasskeyAction.Internal.UnlockResultReceive(
                            result = vaultRepository.unlockVaultWithWebAuthnPrf(
                                credentialId = result.credentialId,
                                prfOutput = prfOutput,
                            ),
                            credentialId = result.credentialId,
                            relyingPartyId = relyingPartyId,
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
            LoginResult.Success -> {
                knownPasskeyManager.recordUse(
                    credentialId = action.credentialId,
                    relyingPartyId = action.relyingPartyId,
                )
                mutableStateFlow.value = state.copy(dialog = null)
            }

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
            VaultUnlockResult.Success -> {
                knownPasskeyManager.recordUse(
                    credentialId = action.credentialId,
                    relyingPartyId = action.relyingPartyId,
                )
                mutableStateFlow.value = state.copy(dialog = null)
            }

            is VaultUnlockResult.AuthenticationError -> showError(
                BitwardenString.this_passkey_cannot_unlock_your_vault.asText(),
            )

            else -> showError(BitwardenString.generic_error_message.asText())
        }
    }

    /**
     * The active account's passkeys that can unlock the vault, followed by the ones it has used
     * that no longer can. Keys already found by [previous] stay found.
     */
    private fun unlockPasskeys(
        request: PasskeyAssertionRequest,
        previous: List<PasskeyItem>,
    ): List<PasskeyItem> {
        val userId = authRepository.userStateFlow.value?.activeUserId
        val known = knownPasskeyManager
            .knownPasskeys
            .filter { it.userId == userId && it.relyingPartyId == request.relyingPartyId }
            .associateBy { it.credentialId }
        val unlockIds = request.allowCredentialIds.map { it.toCanonicalCredentialId() }
        val previousById = previous.associateBy { it.credentialId }
        val unlockable = unlockIds.map { id ->
            val passkey = known[id]
            PasskeyItem(
                credentialId = id,
                name = passkey?.name,
                email = passkey?.email,
                lastUsedEpochMillis = passkey?.lastUsedEpochMillis,
                device = previousById[id]?.device,
                isPinNeeded = previousById[id]?.isPinNeeded == true,
            )
        }
        val retired = known.values
            .filter { it.credentialId !in unlockIds }
            .map { passkey ->
                PasskeyItem(
                    credentialId = passkey.credentialId,
                    name = passkey.name,
                    email = passkey.email,
                    lastUsedEpochMillis = passkey.lastUsedEpochMillis,
                    canUnlock = false,
                )
            }
        return (unlockable + retired).sortedForDisplay()
    }

    /**
     * The passkeys this device has used to log in to [request]'s relying party, marking those in
     * [assertions] as found on [device]. Passkeys the key found that this device hasn't seen are
     * listed by the account name the key reported.
     */
    private fun loginPasskeys(
        request: PasskeyAssertionRequest,
        device: YubiKeyDevice? = null,
        assertions: List<PasskeyAssertionResult.Success> = emptyList(),
    ): List<PasskeyItem> {
        val assertionsById = assertions.associateBy { it.credentialId.toCanonicalCredentialId() }
        val known = knownPasskeyManager
            .knownPasskeys
            .filter { it.relyingPartyId == request.relyingPartyId }
            .distinctBy { it.credentialId }
            .map { passkey ->
                val assertion = assertionsById[passkey.credentialId]
                PasskeyItem(
                    credentialId = passkey.credentialId,
                    name = passkey.name,
                    email = passkey.email ?: assertion?.userName,
                    lastUsedEpochMillis = passkey.lastUsedEpochMillis,
                    device = device.takeIf { assertion != null },
                    assertion = assertion,
                )
            }
        val knownIds = known.map { it.credentialId }.toSet()
        val discovered = assertionsById
            .filterKeys { it !in knownIds }
            .map { (id, assertion) ->
                PasskeyItem(
                    credentialId = id,
                    name = null,
                    email = assertion.userName,
                    lastUsedEpochMillis = null,
                    device = device,
                    assertion = assertion,
                )
            }
        return (known + discovered).sortedForDisplay()
    }

    private fun showError(message: Text) {
        mutableStateFlow.value = state.copy(dialog = PasskeyState.DialogState.Error(message))
    }

    private fun Throwable.toErrorText(): Text =
        message?.asText() ?: BitwardenString.generic_error_message.asText()
}

/**
 * Whether the device stays connected after it is found. A tapped NFC key is usually gone by the
 * time the user has chosen a passkey.
 */
private val YubiKeyDevice.isPersistent: Boolean
    get() = this !is NfcYubiKeyDevice

private fun List<PasskeyItem>.sortedForDisplay(): List<PasskeyItem> =
    sortedWith(
        compareByDescending<PasskeyItem> { it.isSelectable }
            .thenByDescending { it.canUnlock }
            .thenByDescending { it.lastUsedEpochMillis ?: 0L },
    )

/**
 * Whether the passkey flow logs in or unlocks the vault.
 */
enum class PasskeyMode {
    LOGIN,
    UNLOCK,
}

/**
 * What to ask a security key for.
 */
data class AssertionTarget(
    val mode: PasskeyMode,
    val pin: String?,
    val assertionRequest: PasskeyAssertionRequest,
    val loginToken: String?,
)

/**
 * A passkey shown in the passkey list.
 *
 * @property credentialId The base64url-encoded credential ID.
 * @property name The name the user gave the passkey, if known.
 * @property email The email of the passkey's account, if known.
 * @property lastUsedEpochMillis When the passkey was last used on this device, if ever.
 * @property canUnlock Whether the passkey can still unlock the vault or log in.
 * @property device The connected security key that holds the passkey, if one was found.
 * @property isPinNeeded Whether [device] needs its PIN.
 * @property assertion An assertion the key already made with this passkey, if any.
 */
data class PasskeyItem(
    val credentialId: String,
    val name: String?,
    val email: String?,
    val lastUsedEpochMillis: Long?,
    val canUnlock: Boolean = true,
    val device: YubiKeyDevice? = null,
    val isPinNeeded: Boolean = false,
    val assertion: PasskeyAssertionResult.Success? = null,
) {
    /**
     * Whether the passkey is on a connected key and can be chosen.
     */
    val isSelectable: Boolean get() = canUnlock && device != null
}

/**
 * Models the state of the passkey login and unlock flow.
 *
 * @property isUnlockAvailable Whether the active account has a passkey that can unlock the vault.
 * @property isPinRequired Whether the last security key used needed a PIN, so it is asked for
 * before the key is tapped next time. Keys that don't need a PIN ignore it.
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
         * Asks for the security key's PIN, keeping the pending [assertionRequest] if a passkey
         * was already chosen, and the [device] to use if it is still connected.
         */
        data class EnterPin(
            val mode: PasskeyMode,
            val pin: String,
            val assertionRequest: PasskeyAssertionRequest? = null,
            val loginToken: String? = null,
            val device: YubiKeyDevice? = null,
        ) : DialogState()

        /**
         * Lists the user's passkeys while waiting for security keys, marking those on a
         * connected key so one can be chosen.
         *
         * @property isChecking Whether a connected key is being checked for passkeys.
         * @property message A problem with the last key, if any.
         */
        data class ChoosePasskey(
            val mode: PasskeyMode,
            val pin: String?,
            val assertionRequest: PasskeyAssertionRequest,
            val loginToken: String?,
            val passkeys: List<PasskeyItem>,
            val isChecking: Boolean = false,
            val message: Text? = null,
        ) : DialogState() {
            /**
             * The request for any of the listed passkeys.
             */
            val target: AssertionTarget
                get() = AssertionTarget(
                    mode = mode,
                    pin = pin,
                    assertionRequest = assertionRequest,
                    loginToken = loginToken,
                )
        }

        /**
         * Waits for the security key holding the chosen passkey to be tapped or inserted again.
         */
        data class WaitingForKey(
            val mode: PasskeyMode,
            val pin: String?,
            val assertionRequest: PasskeyAssertionRequest,
            val loginToken: String?,
        ) : DialogState() {
            /**
             * The request to send to the key.
             */
            val target: AssertionTarget
                get() = AssertionTarget(
                    mode = mode,
                    pin = pin,
                    assertionRequest = assertionRequest,
                    loginToken = loginToken,
                )
        }

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
     * A security key was tapped or connected.
     */
    data class YubiKeyConnect(val device: YubiKeyDevice) : PasskeyAction()

    /**
     * The user chose the passkey with [credentialId].
     */
    data class PasskeySelect(val credentialId: String) : PasskeyAction()

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
         * The known passkeys' names were refreshed.
         */
        data object KnownPasskeysUpdate : Internal()

        /**
         * A connected security key was checked for passkeys.
         */
        data class ProbeReceive(
            val device: YubiKeyDevice,
            val result: PasskeyProbeResult,
        ) : Internal()

        /**
         * The security key returned a result.
         */
        data class AssertionReceive(
            val target: AssertionTarget,
            val device: YubiKeyDevice,
            val result: PasskeyAssertionResult,
        ) : Internal()

        /**
         * The passkey login completed.
         */
        data class LoginResultReceive(
            val result: LoginResult,
            val credentialId: String,
            val relyingPartyId: String,
        ) : Internal()

        /**
         * The passkey unlock completed.
         */
        data class UnlockResultReceive(
            val result: VaultUnlockResult,
            val credentialId: String,
            val relyingPartyId: String,
        ) : Internal()
    }
}
