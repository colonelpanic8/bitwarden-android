package com.x8bit.bitwarden.data.auth.manager

import com.yubico.yubikit.core.Transport
import com.yubico.yubikit.core.smartcard.SmartCardConnection
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * A smart card connection to a WearAuthn watch over Bluetooth RFCOMM. Each frame in either
 * direction is a 4-byte big-endian length followed by one APDU of the CTAP NFC command set.
 *
 * WearAuthn rejects chained commands that set the NFCCTAP_MSG polling flag, which yubikit always
 * sets, so a chain of short APDUs is sent as a single extended APDU instead. A response that takes
 * longer than [readTimeoutMillis], such as an unanswered approval prompt, closes the connection.
 */
internal class WearAuthnRfcommConnection(
    input: InputStream,
    output: OutputStream,
    private val socket: Closeable,
    private val readTimeoutMillis: Long = READ_TIMEOUT_MILLIS,
) : SmartCardConnection {
    private val input = DataInputStream(input)
    private val output = DataOutputStream(output)
    private val watchdog = Executors.newSingleThreadScheduledExecutor()
    private val chainedData = ByteArrayOutputStream()

    override fun sendAndReceive(apdu: ByteArray): ByteArray {
        val data = apdu.shortApduDataOrNull()
        if (data != null && apdu[0].toInt() and CLA_CHAINING != 0) {
            chainedData.write(data)
            return SW_OK
        }
        if (chainedData.size() == 0) return exchange(apdu)
        data?.let(chainedData::write)
        val combined = chainedData.toByteArray()
        chainedData.reset()
        return exchange(extendedApdu(header = apdu.copyOf(HEADER_SIZE), data = combined))
    }

    override fun getTransport(): Transport = Transport.NFC

    override fun isExtendedLengthApduSupported(): Boolean = true

    override fun getAtr(): ByteArray = ByteArray(0)

    override fun close() {
        watchdog.shutdownNow()
        socket.close()
    }

    private fun exchange(apdu: ByteArray): ByteArray {
        val timeout = watchdog.schedule(::close, readTimeoutMillis, TimeUnit.MILLISECONDS)
        try {
            output.writeInt(apdu.size)
            output.write(apdu)
            output.flush()
            val length = input.readInt()
            if (length !in SW_SIZE..MAX_FRAME_SIZE) {
                throw IOException("Invalid frame length $length")
            }
            return ByteArray(length).also(input::readFully)
        } finally {
            timeout.cancel(false)
        }
    }
}

/**
 * The data field of a short APDU, or `null` if it has none or is an extended APDU.
 */
private fun ByteArray.shortApduDataOrNull(): ByteArray? {
    if (size <= HEADER_SIZE + 1) return null
    val lc = this[HEADER_SIZE].toInt() and BYTE_MASK
    if (lc == 0) return null
    return copyOfRange(HEADER_SIZE + 1, HEADER_SIZE + 1 + lc)
}

private fun extendedApdu(header: ByteArray, data: ByteArray): ByteArray =
    header +
        byteArrayOf(0, (data.size shr Byte.SIZE_BITS).toByte(), data.size.toByte()) +
        data +
        byteArrayOf(0, 0)

private const val HEADER_SIZE = 4
private const val SW_SIZE = 2
private const val CLA_CHAINING = 0x10
private const val BYTE_MASK = 0xFF
private const val MAX_FRAME_SIZE = 65_538
private const val READ_TIMEOUT_MILLIS = 75_000L
private val SW_OK = byteArrayOf(0x90.toByte(), 0x00)
