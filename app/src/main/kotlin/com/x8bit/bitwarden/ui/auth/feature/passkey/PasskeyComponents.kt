package com.x8bit.bitwarden.ui.auth.feature.passkey

import android.Manifest
import android.os.Build
import androidx.activity.compose.LocalActivity
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.clickable
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.bitwarden.ui.platform.components.button.BitwardenOutlinedButton
import com.bitwarden.ui.platform.components.button.BitwardenTextButton
import com.bitwarden.ui.platform.components.dialog.BitwardenBasicDialog
import com.bitwarden.ui.platform.components.dialog.BitwardenLoadingDialog
import com.bitwarden.ui.platform.components.field.BitwardenPasswordField
import com.bitwarden.ui.platform.components.model.CardStyle
import com.bitwarden.ui.platform.resource.BitwardenString
import com.bitwarden.ui.platform.theme.BitwardenTheme
import com.x8bit.bitwarden.data.auth.manager.findWearAuthnWatch
import com.x8bit.bitwarden.ui.platform.composition.LocalPermissionsManager
import com.yubico.yubikit.android.YubiKitManager
import com.yubico.yubikit.android.transport.nfc.NfcConfiguration
import com.yubico.yubikit.android.transport.nfc.NfcNotAvailable
import com.yubico.yubikit.android.transport.usb.UsbConfiguration
import com.yubico.yubikit.core.YubiKeyDevice
import timber.log.Timber

/**
 * A button that logs in with a passkey on a YubiKey.
 */
@Composable
fun PasskeyLoginButton(
    modifier: Modifier = Modifier,
    viewModel: PasskeyViewModel = hiltViewModel(),
) {
    val state by viewModel.stateFlow.collectAsStateWithLifecycle()
    BitwardenOutlinedButton(
        label = stringResource(id = BitwardenString.log_in_with_passkey),
        onClick = { viewModel.trySendAction(PasskeyAction.StartClick(PasskeyMode.LOGIN)) },
        modifier = modifier.testTag("LogInWithPasskeyButton"),
    )
    PasskeyDialogs(dialog = state.dialog, onAction = viewModel::trySendAction)
}

/**
 * A button that unlocks the vault with a passkey on a YubiKey. It is only shown when one of the
 * active account's passkeys can unlock the vault.
 */
@Composable
fun PasskeyUnlockButton(
    modifier: Modifier = Modifier,
    viewModel: PasskeyViewModel = hiltViewModel(),
) {
    val state by viewModel.stateFlow.collectAsStateWithLifecycle()
    if (!state.isUnlockAvailable) return
    BitwardenOutlinedButton(
        label = stringResource(id = BitwardenString.unlock_with_passkey),
        onClick = { viewModel.trySendAction(PasskeyAction.StartClick(PasskeyMode.UNLOCK)) },
        modifier = modifier.testTag("UnlockWithPasskeyButton"),
    )
    PasskeyDialogs(dialog = state.dialog, onAction = viewModel::trySendAction)
}

@Composable
private fun PasskeyDialogs(
    dialog: PasskeyState.DialogState?,
    onAction: (PasskeyAction) -> Unit,
) {
    val onDismiss = { onAction(PasskeyAction.DialogDismiss) }
    when (dialog) {
        is PasskeyState.DialogState.EnterPin -> PasskeyPinDialog(
            pin = dialog.pin,
            onPinChange = { onAction(PasskeyAction.PinChange(it)) },
            onSubmit = { onAction(PasskeyAction.PinSubmit) },
            onDismiss = onDismiss,
        )

        is PasskeyState.DialogState.ChoosePasskey -> {
            val onDeviceConnect = { device: YubiKeyDevice ->
                onAction(PasskeyAction.YubiKeyConnect(device))
            }
            YubiKeyDiscoveryEffect(onDeviceConnect = onDeviceConnect)
            WearAuthnWatchDiscoveryEffect(onDeviceConnect = onDeviceConnect)
            ChoosePasskeyDialog(
                dialog = dialog,
                onPasskeySelect = { onAction(PasskeyAction.PasskeySelect(it)) },
                onDismiss = onDismiss,
            )
        }

        is PasskeyState.DialogState.WaitingForKey -> {
            YubiKeyDiscoveryEffect(onDeviceConnect = { onAction(PasskeyAction.YubiKeyConnect(it)) })
            AlertDialog(
                onDismissRequest = onDismiss,
                confirmButton = {
                    BitwardenTextButton(
                        label = stringResource(id = BitwardenString.cancel),
                        onClick = onDismiss,
                    )
                },
                title = { Text(text = stringResource(id = BitwardenString.security_key)) },
                text = {
                    Text(
                        text = stringResource(
                            id = BitwardenString.hold_the_security_key_with_the_passkey_you_chose,
                        ),
                    )
                },
                shape = BitwardenTheme.shapes.dialog,
                containerColor = BitwardenTheme.colorScheme.background.primary,
                titleContentColor = BitwardenTheme.colorScheme.text.primary,
                textContentColor = BitwardenTheme.colorScheme.text.primary,
            )
        }

        is PasskeyState.DialogState.Loading -> BitwardenLoadingDialog(text = dialog.message())

        is PasskeyState.DialogState.Error -> BitwardenBasicDialog(
            title = stringResource(id = BitwardenString.an_error_has_occurred),
            message = dialog.message(),
            onDismissRequest = onDismiss,
        )

        null -> Unit
    }
}

@Composable
private fun ChoosePasskeyDialog(
    dialog: PasskeyState.DialogState.ChoosePasskey,
    onPasskeySelect: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            BitwardenTextButton(
                label = stringResource(id = BitwardenString.cancel),
                onClick = onDismiss,
            )
        },
        title = { Text(text = stringResource(id = BitwardenString.choose_a_passkey)) },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                Text(
                    text = stringResource(
                        id = BitwardenString.tap_or_connect_your_security_key_to_see_its_passkeys,
                    ),
                    style = BitwardenTheme.typography.bodyMedium,
                )
                val status = when {
                    dialog.isChecking -> stringResource(id = BitwardenString.checking_security_key)
                    else -> dialog.message?.invoke()
                }
                status?.let {
                    Spacer(modifier = Modifier.height(height = 8.dp))
                    Text(
                        text = it,
                        style = BitwardenTheme.typography.bodyMedium,
                        color = BitwardenTheme.colorScheme.text.secondary,
                    )
                }
                Spacer(modifier = Modifier.height(height = 8.dp))
                dialog.passkeys.forEach { passkey ->
                    PasskeyRow(
                        passkey = passkey,
                        onClick = { onPasskeySelect(passkey.credentialId) },
                    )
                }
            }
        },
        shape = BitwardenTheme.shapes.dialog,
        containerColor = BitwardenTheme.colorScheme.background.primary,
        titleContentColor = BitwardenTheme.colorScheme.text.primary,
        textContentColor = BitwardenTheme.colorScheme.text.primary,
    )
}

@Composable
private fun PasskeyRow(
    passkey: PasskeyItem,
    onClick: () -> Unit,
) {
    val status = stringResource(
        id = when {
            !passkey.canUnlock -> BitwardenString.can_no_longer_be_used
            passkey.isSelectable -> BitwardenString.on_a_connected_security_key
            else -> BitwardenString.not_on_a_connected_security_key
        },
    )
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = passkey.isSelectable, onClick = onClick)
            .alpha(if (passkey.isSelectable) 1f else DISABLED_ROW_ALPHA)
            .padding(vertical = 8.dp)
            .testTag("PasskeyRow"),
    ) {
        Text(
            text = passkey.name ?: stringResource(id = BitwardenString.unnamed_passkey),
            style = BitwardenTheme.typography.bodyLarge,
            color = BitwardenTheme.colorScheme.text.primary,
        )
        Text(
            text = listOfNotNull(passkey.email, status).joinToString(separator = " · "),
            style = BitwardenTheme.typography.bodySmall,
            color = BitwardenTheme.colorScheme.text.secondary,
        )
    }
}

private const val DISABLED_ROW_ALPHA = 0.5f

@Composable
private fun PasskeyPinDialog(
    pin: String,
    onPinChange: (String) -> Unit,
    onSubmit: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        dismissButton = {
            BitwardenTextButton(
                label = stringResource(id = BitwardenString.cancel),
                onClick = onDismiss,
            )
        },
        confirmButton = {
            BitwardenTextButton(
                label = stringResource(id = BitwardenString.continue_text),
                onClick = onSubmit,
                isEnabled = pin.isNotEmpty(),
                modifier = Modifier.testTag("PasskeyPinContinueButton"),
            )
        },
        title = { Text(text = stringResource(id = BitwardenString.security_key_pin)) },
        text = {
            Column {
                Text(
                    text = stringResource(id = BitwardenString.this_security_key_needs_its_pin),
                    style = BitwardenTheme.typography.bodyMedium,
                )
                Spacer(modifier = Modifier.height(height = 16.dp))
                BitwardenPasswordField(
                    label = stringResource(id = BitwardenString.pin),
                    value = pin,
                    onValueChange = onPinChange,
                    supportingContent = null,
                    cardStyle = CardStyle.Full,
                    autoFocus = true,
                    keyboardType = KeyboardType.NumberPassword,
                    imeAction = ImeAction.Done,
                    keyboardActions = KeyboardActions(onDone = { onSubmit() }),
                    passwordFieldTestTag = "PasskeyPinEntry",
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        shape = BitwardenTheme.shapes.dialog,
        containerColor = BitwardenTheme.colorScheme.background.primary,
        titleContentColor = BitwardenTheme.colorScheme.text.primary,
        textContentColor = BitwardenTheme.colorScheme.text.primary,
    )
}

/**
 * Listens for YubiKeys tapped over NFC or connected over USB while in composition.
 */
@Composable
private fun YubiKeyDiscoveryEffect(onDeviceConnect: (YubiKeyDevice) -> Unit) {
    val context = LocalContext.current
    val activity = LocalActivity.current
    val currentOnDeviceConnect by rememberUpdatedState(onDeviceConnect)
    DisposableEffect(context, activity) {
        val manager = YubiKitManager(context.applicationContext)
        manager.startUsbDiscovery(UsbConfiguration()) { currentOnDeviceConnect(it) }
        val isNfcEnabled = activity?.let {
            try {
                manager.startNfcDiscovery(NfcConfiguration(), it) { device ->
                    currentOnDeviceConnect(device)
                }
                true
            } catch (e: NfcNotAvailable) {
                Timber.w(e, "NFC is unavailable for security keys")
                false
            }
        } == true
        onDispose {
            manager.stopUsbDiscovery()
            if (isNfcEnabled) activity?.let(manager::stopNfcDiscovery)
        }
    }
}

/**
 * Looks for a paired watch running WearAuthn while in composition, asking for the Bluetooth
 * permission first if needed.
 */
@Composable
private fun WearAuthnWatchDiscoveryEffect(onDeviceConnect: (YubiKeyDevice) -> Unit) {
    val context = LocalContext.current
    val permissionsManager = LocalPermissionsManager.current
    val currentOnDeviceConnect by rememberUpdatedState(onDeviceConnect)
    var isPermissionGranted by remember {
        mutableStateOf(
            Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
                permissionsManager.checkPermission(Manifest.permission.BLUETOOTH_CONNECT),
        )
    }
    val permissionLauncher = permissionsManager.getLauncher { isPermissionGranted = it }
    LaunchedEffect(isPermissionGranted) {
        if (isPermissionGranted) {
            findWearAuthnWatch(context)?.let(currentOnDeviceConnect)
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            permissionLauncher.launch(Manifest.permission.BLUETOOTH_CONNECT)
        }
    }
}
