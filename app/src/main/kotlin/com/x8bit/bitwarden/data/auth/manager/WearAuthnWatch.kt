package com.x8bit.bitwarden.data.auth.manager

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.ParcelUuid
import android.os.Parcelable
import androidx.core.content.ContextCompat
import androidx.core.content.IntentCompat
import com.yubico.yubikit.core.Transport
import com.yubico.yubikit.core.YubiKeyConnection
import com.yubico.yubikit.core.YubiKeyDevice
import com.yubico.yubikit.core.smartcard.SmartCardConnection
import com.yubico.yubikit.core.util.Callback
import com.yubico.yubikit.core.util.Result
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import java.io.IOException
import java.util.UUID
import kotlin.concurrent.thread

/**
 * The RFCOMM service a watch running WearAuthn serves CTAP on.
 */
val WEARAUTHN_SERVICE_UUID: UUID = UUID.fromString("5c1b1d3a-62a4-4f4e-9f0e-8a7c2d1e6b3f")

private const val SDP_TIMEOUT_MILLIS = 12_000L

/**
 * A paired watch running WearAuthn, used as a FIDO2 authenticator over Bluetooth RFCOMM. The watch
 * asks for approval on its own screen.
 */
class WearAuthnWatchDevice(private val device: BluetoothDevice) : YubiKeyDevice {

    override fun getTransport(): Transport = Transport.NFC

    override fun supportsConnection(connectionType: Class<out YubiKeyConnection>): Boolean =
        connectionType.isAssignableFrom(SmartCardConnection::class.java)

    @SuppressLint("MissingPermission")
    override fun <T : YubiKeyConnection> openConnection(connectionType: Class<T>): T {
        require(supportsConnection(connectionType)) { "Unsupported connection $connectionType" }
        val socket = device.createRfcommSocketToServiceRecord(WEARAUTHN_SERVICE_UUID)
        try {
            socket.connect()
        } catch (e: IOException) {
            socket.close()
            throw e
        }
        return connectionType.cast(
            WearAuthnRfcommConnection(
                input = socket.inputStream,
                output = socket.outputStream,
                socket = socket,
            ),
        )
    }

    override fun <T : YubiKeyConnection> requestConnection(
        connectionType: Class<T>,
        callback: Callback<Result<T, IOException>>,
    ) {
        thread {
            callback.invoke(
                try {
                    Result.success(openConnection(connectionType))
                } catch (e: IOException) {
                    Result.failure(e)
                },
            )
        }
    }
}

/**
 * Finds a bonded watch that serves WearAuthn over RFCOMM, or `null` if there is none or Bluetooth
 * is off. Cached service UUIDs can predate WearAuthn's service, so when no bonded device
 * advertises it, bonded devices are asked again over SDP. Needs the `BLUETOOTH_CONNECT`
 * permission.
 */
@SuppressLint("MissingPermission")
suspend fun findWearAuthnWatch(context: Context): WearAuthnWatchDevice? {
    val adapter = context.getSystemService(BluetoothManager::class.java)?.adapter
    if (adapter?.isEnabled != true) return null
    val bondedDevices = adapter.bondedDevices.orEmpty()
    val device = bondedDevices.firstOrNull { it.uuids.servesWearAuthn() }
        ?: withTimeoutOrNull(SDP_TIMEOUT_MILLIS) {
            sdpResults(context = context, devices = bondedDevices)
                .first { (_, uuids) -> uuids.servesWearAuthn() }
                .first
        }
    return device?.let(::WearAuthnWatchDevice)
}

@SuppressLint("MissingPermission")
private fun sdpResults(context: Context, devices: Set<BluetoothDevice>) = callbackFlow {
    val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val device = IntentCompat.getParcelableExtra(
                intent,
                BluetoothDevice.EXTRA_DEVICE,
                BluetoothDevice::class.java,
            ) ?: return
            val uuids = IntentCompat.getParcelableArrayExtra(
                intent,
                BluetoothDevice.EXTRA_UUID,
                ParcelUuid::class.java,
            )
            trySend(device to uuids)
        }
    }
    ContextCompat.registerReceiver(
        context,
        receiver,
        IntentFilter(BluetoothDevice.ACTION_UUID),
        ContextCompat.RECEIVER_EXPORTED,
    )
    devices.forEach { it.fetchUuidsWithSdp() }
    awaitClose { context.unregisterReceiver(receiver) }
}

private fun Array<out Parcelable>?.servesWearAuthn(): Boolean =
    orEmpty().any { (it as? ParcelUuid)?.uuid == WEARAUTHN_SERVICE_UUID }
