package com.x8bit.bitwarden.data.auth.manager

import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.DataInputStream
import java.io.DataOutputStream

class WearAuthnRfcommConnectionTest {

    private val sent = ByteArrayOutputStream()

    private fun connection(vararg responses: ByteArray): WearAuthnRfcommConnection {
        val frames = ByteArrayOutputStream()
        DataOutputStream(frames).use { out ->
            responses.forEach {
                out.writeInt(it.size)
                out.write(it)
            }
        }
        return WearAuthnRfcommConnection(
            input = ByteArrayInputStream(frames.toByteArray()),
            output = sent,
            socket = Closeable {},
        )
    }

    private fun sentFrames(): List<ByteArray> {
        val input = DataInputStream(ByteArrayInputStream(sent.toByteArray()))
        return buildList {
            while (input.available() > 0) {
                add(ByteArray(input.readInt()).also(input::readFully))
            }
        }
    }

    @Test
    fun `sendAndReceive should frame unchained APDUs unchanged`() {
        val select = hex("00a4040008a0000006472f0001")
        val connection = connection(hex("5532465f56329000"))

        assertArrayEquals(hex("5532465f56329000"), connection.sendAndReceive(select))
        assertEquals(listOf(select.toList()), sentFrames().map { it.toList() })
    }

    @Test
    fun `sendAndReceive should send a chain of short APDUs as one extended APDU`() {
        val first = ByteArray(255) { 1 }
        val last = byteArrayOf(2, 3, 4)
        val connection = connection(hex("a1029000"))

        assertArrayEquals(
            hex("9000"),
            connection.sendAndReceive(hex("90108000ff") + first),
        )
        assertArrayEquals(
            hex("a1029000"),
            connection.sendAndReceive(hex("8010800003") + last),
        )
        assertEquals(
            listOf((hex("8010800000" + "0102") + first + last + hex("0000")).toList()),
            sentFrames().map { it.toList() },
        )
    }
}

private fun hex(value: String): ByteArray =
    value.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
