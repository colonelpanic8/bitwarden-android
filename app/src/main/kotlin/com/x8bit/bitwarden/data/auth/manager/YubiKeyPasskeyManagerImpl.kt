package com.x8bit.bitwarden.data.auth.manager

import com.bitwarden.core.data.manager.dispatcher.DispatcherManager
import com.x8bit.bitwarden.data.auth.manager.model.PasskeyAssertionRequest
import com.x8bit.bitwarden.data.auth.manager.model.PasskeyAssertionResult
import com.x8bit.bitwarden.data.auth.manager.util.buildWebAuthnAssertionDeviceResponse
import com.x8bit.bitwarden.data.auth.manager.util.buildWebAuthnGetClientDataJson
import com.x8bit.bitwarden.data.auth.manager.util.toBase64Url
import com.x8bit.bitwarden.data.auth.repository.util.decodeBase64UrlOrNull
import com.yubico.yubikit.core.YubiKeyDevice
import com.yubico.yubikit.core.YubiKeyConnection
import com.yubico.yubikit.core.application.CommandException
import com.yubico.yubikit.core.fido.FidoConnection
import com.yubico.yubikit.core.smartcard.SmartCardConnection
import com.yubico.yubikit.fido.client.AuthInvalidClientError
import com.yubico.yubikit.fido.client.ClientError
import com.yubico.yubikit.fido.client.Ctap2Client
import com.yubico.yubikit.fido.client.MultipleAssertionsAvailable
import com.yubico.yubikit.fido.client.clientdata.ClientDataProvider
import com.yubico.yubikit.fido.ctap.Ctap2Session
import com.yubico.yubikit.fido.webauthn.AuthenticatorAssertionResponse
import com.yubico.yubikit.fido.webauthn.Extensions
import com.yubico.yubikit.fido.webauthn.PublicKeyCredential
import com.yubico.yubikit.fido.webauthn.PublicKeyCredentialDescriptor
import com.yubico.yubikit.fido.webauthn.PublicKeyCredentialRequestOptions
import com.yubico.yubikit.fido.webauthn.PublicKeyCredentialType
import com.yubico.yubikit.fido.webauthn.SerializationType
import com.yubico.yubikit.fido.webauthn.UserVerificationRequirement
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.io.IOException
import java.net.URI

/**
 * Default implementation of [YubiKeyPasskeyManager], built on yubikit-android's CTAP2 client.
 */
class YubiKeyPasskeyManagerImpl(
    private val dispatcherManager: DispatcherManager,
) : YubiKeyPasskeyManager {

    override suspend fun getAssertion(
        device: YubiKeyDevice,
        request: PasskeyAssertionRequest,
        pin: String,
    ): PasskeyAssertionResult = withContext(dispatcherManager.io) {
        try {
            device.openPreferredConnection().use { connection ->
                getAssertion(connection = connection, request = request, pin = pin)
            }
        } catch (e: AuthInvalidClientError) {
            if (e.authType == AuthInvalidClientError.AuthType.PIN) {
                PasskeyAssertionResult.InvalidPin(retriesRemaining = e.retries)
            } else {
                PasskeyAssertionResult.Error(error = e)
            }
        } catch (e: ClientError) {
            Timber.w(e, "Security key rejected the passkey assertion")
            if (e.errorCode == ClientError.Code.DEVICE_INELIGIBLE) {
                PasskeyAssertionResult.NoCredentials
            } else {
                PasskeyAssertionResult.Error(error = e)
            }
        } catch (e: IOException) {
            PasskeyAssertionResult.Error(error = e)
        } catch (e: CommandException) {
            PasskeyAssertionResult.Error(error = e)
        } catch (e: IllegalArgumentException) {
            PasskeyAssertionResult.Error(error = e)
        }
    }

    private fun getAssertion(
        connection: YubiKeyConnection,
        request: PasskeyAssertionRequest,
        pin: String,
    ): PasskeyAssertionResult {
        val session = when (connection) {
            is FidoConnection -> Ctap2Session(connection)
            is SmartCardConnection -> Ctap2Session(connection)
            else -> throw IOException("Unsupported connection")
        }
        val clientDataJson = buildWebAuthnGetClientDataJson(
            challenge = request.challenge,
            origin = request.origin,
        )
            .toByteArray(Charsets.UTF_8)
        val options = PublicKeyCredentialRequestOptions(
            requireNotNull(request.challenge.decodeBase64UrlOrNull()) { "Invalid challenge" },
            request.timeoutMillis,
            request.relyingPartyId,
            request.allowCredentialIds.map { id ->
                PublicKeyCredentialDescriptor(
                    PublicKeyCredentialType.PUBLIC_KEY,
                    requireNotNull(id.decodeBase64UrlOrNull()) { "Invalid credential ID" },
                )
            },
            UserVerificationRequirement.REQUIRED,
            Extensions.fromMap(
                mapOf("prf" to mapOf("eval" to mapOf("first" to request.prfSalt))),
            ),
        )
        val credential = try {
            Ctap2Client(session).getAssertion(
                ClientDataProvider.fromClientDataJson(clientDataJson),
                options,
                requireNotNull(URI(request.origin).host) { "Invalid origin" },
                pin.toCharArray(),
                null,
            )
        } catch (e: MultipleAssertionsAvailable) {
            e.select(0)
        }
        return credential.toAssertionResult()
    }
}

private fun YubiKeyDevice.openPreferredConnection(): YubiKeyConnection =
    if (supportsConnection(FidoConnection::class.java)) {
        openConnection(FidoConnection::class.java)
    } else {
        openConnection(SmartCardConnection::class.java)
    }

private fun PublicKeyCredential.toAssertionResult(): PasskeyAssertionResult.Success {
    val response = response as AuthenticatorAssertionResponse
    val prf = clientExtensionResults?.toMap(SerializationType.JSON)?.get("prf") as? Map<*, *>
    val results = prf?.get("results") as? Map<*, *>
    return PasskeyAssertionResult.Success(
        credentialId = rawId.toBase64Url(),
        deviceResponse = buildWebAuthnAssertionDeviceResponse(
            rawId = rawId,
            authenticatorData = response.authenticatorData,
            clientDataJson = response.clientDataJson,
            signature = response.signature,
            userHandle = response.userHandle,
        ),
        prfOutput = results?.get("first") as? String,
    )
}
