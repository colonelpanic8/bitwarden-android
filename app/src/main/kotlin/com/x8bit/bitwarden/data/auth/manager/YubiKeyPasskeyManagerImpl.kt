package com.x8bit.bitwarden.data.auth.manager

import com.bitwarden.core.data.manager.dispatcher.DispatcherManager
import com.x8bit.bitwarden.data.auth.manager.model.PasskeyAssertionRequest
import com.x8bit.bitwarden.data.auth.manager.model.PasskeyAssertionResult
import com.x8bit.bitwarden.data.auth.manager.model.PasskeyProbeResult
import com.x8bit.bitwarden.data.auth.manager.util.buildWebAuthnAssertionDeviceResponse
import com.x8bit.bitwarden.data.auth.manager.util.buildWebAuthnGetClientDataJson
import com.x8bit.bitwarden.data.auth.manager.util.toBase64Url
import com.x8bit.bitwarden.data.auth.repository.util.decodeBase64UrlOrNull
import com.yubico.yubikit.core.YubiKeyConnection
import com.yubico.yubikit.core.YubiKeyDevice
import com.yubico.yubikit.core.application.CommandException
import com.yubico.yubikit.core.application.CommandState
import com.yubico.yubikit.core.fido.CtapException
import com.yubico.yubikit.core.fido.FidoConnection
import com.yubico.yubikit.core.smartcard.SmartCardConnection
import com.yubico.yubikit.core.util.Pair
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
import com.yubico.yubikit.fido.webauthn.ClientExtensionResults
import com.yubico.yubikit.fido.webauthn.Extensions
import com.yubico.yubikit.fido.webauthn.PublicKeyCredential
import com.yubico.yubikit.fido.webauthn.PublicKeyCredentialDescriptor
import com.yubico.yubikit.fido.webauthn.PublicKeyCredentialEntity
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

    override suspend fun findCredentials(
        device: YubiKeyDevice,
        relyingPartyId: String,
        credentialIds: List<String>,
    ): PasskeyProbeResult = withContext(dispatcherManager.io) {
        try {
            device.openPreferredConnection().use { connection ->
                val session = connection.toCtap2Session()
                PasskeyProbeResult.Found(
                    credentialIds = session.findCredentials(relyingPartyId, credentialIds),
                    isPinNeeded = session.cachedInfo.options.requiresClientPin(),
                )
            }
        } catch (e: IOException) {
            PasskeyProbeResult.Error(error = e)
        } catch (e: CommandException) {
            PasskeyProbeResult.Error(error = e)
        }
    }

    private fun getAssertion(
        connection: YubiKeyConnection,
        request: PasskeyAssertionRequest,
        pin: String?,
    ): PasskeyAssertionResult {
        val session = connection.toCtap2Session()
        val isPinNeeded = session.cachedInfo.options.requiresClientPin()
        if (isPinNeeded && pin == null) return PasskeyAssertionResult.PinRequired
        val clientDataJson = buildWebAuthnGetClientDataJson(
            challenge = request.challenge,
            origin = request.origin,
        )
            .toByteArray(Charsets.UTF_8)
        val client = AllAssertionsCtap2Client(session)
        // Ask for one allowed credential at a time. Given several, yubikit narrows the list to the
        // authenticator's credential and then needs the response to name it, which authenticators
        // such as WearAuthn may omit for a single-credential list.
        val allowLists = request.allowCredentialIds
            .map { listOf(it) }
            .ifEmpty { listOf(emptyList()) }
        allowLists.forEachIndexed { index, credentialIds ->
            try {
                val assertions = client
                    .getAllAssertions(
                        request = request,
                        clientDataJson = clientDataJson,
                        credentialIds = credentialIds,
                        // yubikit prefers a given PIN over built-in user verification.
                        pin = pin.takeIf { isPinNeeded },
                    )
                    .map { it.toAssertionResult(isPinUsed = isPinNeeded) }
                return assertions.singleOrNull()
                    ?: PasskeyAssertionResult.MultipleCredentials(assertions = assertions)
            } catch (e: ClientError) {
                val hasNoCredential = e.errorCode == ClientError.Code.DEVICE_INELIGIBLE
                if (!hasNoCredential || index == allowLists.lastIndex) throw e
            }
        }
        error("No allow list was tried")
    }
}

/**
 * A [Ctap2Client] that returns every assertion when a discoverable request matches several
 * credentials, instead of making the caller select one.
 */
private class AllAssertionsCtap2Client(
    session: Ctap2Session,
) : Ctap2Client(session, listOf(PinlessHmacSecretExtension())) {
    private var lastAssertions: List<Pair<Ctap2Session.AssertionData, ClientExtensionResults>> =
        emptyList()

    override fun ctapGetAssertions(
        clientDataHash: ByteArray,
        options: PublicKeyCredentialRequestOptions,
        effectiveDomain: String,
        pin: CharArray?,
        state: CommandState?,
    ): List<Pair<Ctap2Session.AssertionData, ClientExtensionResults>> =
        super
            .ctapGetAssertions(clientDataHash, options, effectiveDomain, pin, state)
            .also { lastAssertions = it }

    fun getAllAssertions(
        request: PasskeyAssertionRequest,
        clientDataJson: ByteArray,
        credentialIds: List<String>,
        pin: String?,
    ): List<UserAssertion> {
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
        try {
            getAssertion(
                ClientDataProvider.fromClientDataJson(clientDataJson),
                options,
                requireNotNull(URI(request.origin).host) { "Invalid origin" },
                pin?.toCharArray(),
                null,
            )
        } catch (_: MultipleAssertionsAvailable) {
            // Every assertion is built from lastAssertions below.
        }
        return lastAssertions.map { (assertion, extensionResults) ->
            UserAssertion(
                credential = PublicKeyCredential.fromAssertion(
                    assertion,
                    clientDataJson,
                    options.allowCredentials,
                    extensionResults,
                ),
                userName = assertion.user?.get(PublicKeyCredentialEntity.NAME) as? String,
            )
        }
    }
}

private data class UserAssertion(
    val credential: PublicKeyCredential,
    val userName: String?,
)

private operator fun <A : Any, B : Any> Pair<A, B>.component1(): A = first

private operator fun <A : Any, B : Any> Pair<A, B>.component2(): B = second

private fun YubiKeyConnection.toCtap2Session(): Ctap2Session = when (this) {
    is FidoConnection -> Ctap2Session(this)
    is SmartCardConnection -> Ctap2Session(this)
    else -> throw IOException("Unsupported connection")
}

/**
 * The [credentialIds] this authenticator holds for [relyingPartyId], checked one at a time
 * without user presence or verification, or `null` if the authenticator does not support the
 * check. Credentials that require user verification to be found are reported as absent.
 */
private fun Ctap2Session.findCredentials(
    relyingPartyId: String,
    credentialIds: List<String>,
): List<String>? {
    val maxCredentialIdLength = cachedInfo.maxCredentialIdLength
    return credentialIds.filter { id ->
        val rawId = id.decodeBase64UrlOrNull() ?: return@filter false
        if (maxCredentialIdLength != null && rawId.size > maxCredentialIdLength) {
            return@filter false
        }
        try {
            getAssertions(
                relyingPartyId,
                ByteArray(size = CLIENT_DATA_HASH_LENGTH),
                listOf(
                    PublicKeyCredentialDescriptor(PublicKeyCredentialType.PUBLIC_KEY, rawId)
                        .toMap(SerializationType.CBOR),
                ),
                null,
                mapOf("up" to false),
                null,
                null,
                null,
            )
            true
        } catch (e: CtapException) {
            if (e.ctapError != CtapException.ERR_NO_CREDENTIALS) {
                Timber.w(e, "Security key cannot be checked for passkeys")
                return null
            }
            false
        }
    }
}

private const val CLIENT_DATA_HASH_LENGTH = 32

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

private fun UserAssertion.toAssertionResult(
    isPinUsed: Boolean,
): PasskeyAssertionResult.Success = with(credential) {
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
        userName = userName,
    )
}
