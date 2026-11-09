package com.x8bit.bitwarden.data.auth.manager

import com.bitwarden.core.data.manager.dispatcher.DispatcherManager
import com.x8bit.bitwarden.data.auth.manager.model.PasskeyAssertionRequest
import com.x8bit.bitwarden.data.auth.manager.model.PasskeyAssertionResult
import com.x8bit.bitwarden.data.auth.manager.util.buildWebAuthnAssertionDeviceResponse
import com.x8bit.bitwarden.data.auth.manager.util.buildWebAuthnGetClientDataJson
import com.x8bit.bitwarden.data.auth.manager.util.toBase64Url
import com.x8bit.bitwarden.data.auth.repository.util.decodeBase64UrlOrNull
import com.yubico.yubikit.core.YubiKeyConnection
import com.yubico.yubikit.core.YubiKeyDevice
import com.yubico.yubikit.core.application.CommandException
import com.yubico.yubikit.core.fido.FidoConnection
import com.yubico.yubikit.core.smartcard.SmartCardConnection
import com.yubico.yubikit.fido.client.AuthInvalidClientError
import com.yubico.yubikit.fido.client.ClientError
import com.yubico.yubikit.fido.client.Ctap2Client
import com.yubico.yubikit.fido.client.MultipleAssertionsAvailable
import com.yubico.yubikit.fido.client.clientdata.ClientDataProvider
import com.yubico.yubikit.fido.client.extensions.Extension.AuthenticationProcessor
import com.yubico.yubikit.fido.client.extensions.HmacSecretExtension
import com.yubico.yubikit.fido.ctap.Ctap2Session
import com.yubico.yubikit.fido.ctap.PinUvAuthDummyProtocol
import com.yubico.yubikit.fido.ctap.PinUvAuthProtocol
import com.yubico.yubikit.fido.ctap.PinUvAuthProtocolV1
import com.yubico.yubikit.fido.ctap.PinUvAuthProtocolV2
import com.yubico.yubikit.fido.ctap.UnsupportedPinUvAuthProtocolError
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
        pin: String?,
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
            when (e.errorCode) {
                ClientError.Code.DEVICE_INELIGIBLE -> PasskeyAssertionResult.NoCredentials
                ClientError.Code.CONFIGURATION_UNSUPPORTED -> {
                    PasskeyAssertionResult.UserVerificationUnavailable
                }

                else -> PasskeyAssertionResult.Error(error = e)
            }
        } catch (e: IOException) {
            assertionError(e)
        } catch (e: CommandException) {
            assertionError(e)
        } catch (e: IllegalArgumentException) {
            assertionError(e)
        } catch (e: UnsupportedPinUvAuthProtocolError) {
            assertionError(e)
        }
    }

    private fun assertionError(error: Throwable): PasskeyAssertionResult.Error {
        Timber.w(error, "Getting a passkey assertion from the security key failed")
        return PasskeyAssertionResult.Error(error = error)
    }

    private fun getAssertion(
        connection: YubiKeyConnection,
        request: PasskeyAssertionRequest,
        pin: String?,
    ): PasskeyAssertionResult {
        val session = when (connection) {
            is FidoConnection -> Ctap2Session(connection)
            is SmartCardConnection -> Ctap2Session(connection)
            else -> throw IOException("Unsupported connection")
        }
        val isPinNeeded = session.cachedInfo.options.requiresClientPin()
        if (isPinNeeded && pin == null) return PasskeyAssertionResult.PinRequired
        val clientDataJson = buildWebAuthnGetClientDataJson(
            challenge = request.challenge,
            origin = request.origin,
        )
            .toByteArray(Charsets.UTF_8)
        val client = Ctap2Client(session, listOf(PinlessHmacSecretExtension()))
        // Ask for one allowed credential at a time. Given several, yubikit narrows the list to the
        // authenticator's credential and then needs the response to name it, which authenticators
        // such as WearAuthn may omit for a single-credential list.
        val allowLists = request.allowCredentialIds
            .map { listOf(it) }
            .ifEmpty { listOf(emptyList()) }
        allowLists.forEachIndexed { index, credentialIds ->
            try {
                return client
                    // yubikit prefers a given PIN over built-in user verification.
                    .getAssertion(
                        request = request,
                        clientDataJson = clientDataJson,
                        credentialIds = credentialIds,
                        pin = pin.takeIf { isPinNeeded },
                    )
                    .toAssertionResult(isPinUsed = isPinNeeded)
            } catch (e: ClientError) {
                val hasNoCredential = e.errorCode == ClientError.Code.DEVICE_INELIGIBLE
                if (!hasNoCredential || index == allowLists.lastIndex) throw e
            }
        }
        error("No allow list was tried")
    }
}

private fun Ctap2Client.getAssertion(
    request: PasskeyAssertionRequest,
    clientDataJson: ByteArray,
    credentialIds: List<String>,
    pin: String?,
): PublicKeyCredential {
    val options = PublicKeyCredentialRequestOptions(
        requireNotNull(request.challenge.decodeBase64UrlOrNull()) { "Invalid challenge" },
        request.timeoutMillis,
        request.relyingPartyId,
        credentialIds.map { id ->
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
    return try {
        getAssertion(
            ClientDataProvider.fromClientDataJson(clientDataJson),
            options,
            requireNotNull(URI(request.origin).host) { "Invalid origin" },
            pin?.toCharArray(),
            null,
        )
    } catch (e: MultipleAssertionsAvailable) {
        e.select(0)
    }
}

/**
 * Whether user verification needs the client PIN: the authenticator has a PIN set and no built-in
 * user verification of its own.
 */
internal fun Map<String, *>.requiresClientPin(): Boolean =
    this["uv"] != true && this["clientPin"] == true

/**
 * The PIN/UV auth protocol to use for the hmac-secret key agreement, preferring version 2, or
 * `null` if the authenticator supports neither.
 */
internal fun List<Int>.toPinUvAuthProtocolOrNull(): PinUvAuthProtocol? = when {
    PinUvAuthProtocolV2.VERSION in this -> PinUvAuthProtocolV2()
    PinUvAuthProtocolV1.VERSION in this -> PinUvAuthProtocolV1()
    else -> null
}

/**
 * hmac-secret for authenticators without a client PIN. yubikit only negotiates a PIN/UV auth
 * protocol when the authenticator reports the `clientPin` option, but hmac-secret needs one for
 * its key agreement either way, so fall back to the authenticator's advertised protocols.
 */
private class PinlessHmacSecretExtension : HmacSecretExtension() {
    override fun getAssertion(
        ctap: Ctap2Session,
        options: PublicKeyCredentialRequestOptions,
        pinUvAuthProtocol: PinUvAuthProtocol,
    ): AuthenticationProcessor? = super.getAssertion(
        ctap,
        options,
        pinUvAuthProtocol
            .takeUnless { it is PinUvAuthDummyProtocol }
            ?: ctap.cachedInfo.pinUvAuthProtocols.toPinUvAuthProtocolOrNull()
            ?: pinUvAuthProtocol,
    )
}

private fun YubiKeyDevice.openPreferredConnection(): YubiKeyConnection =
    if (supportsConnection(FidoConnection::class.java)) {
        openConnection(FidoConnection::class.java)
    } else {
        openConnection(SmartCardConnection::class.java)
    }

private fun PublicKeyCredential.toAssertionResult(
    isPinUsed: Boolean,
): PasskeyAssertionResult.Success {
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
        isPinUsed = isPinUsed,
    )
}
