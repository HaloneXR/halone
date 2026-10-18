package dev.halone.desktop.shell

import dev.halone.shell.BridgeChannel
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.DataInputStream
import java.io.OutputStream
import java.net.Socket
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

class BridgeSocketsTest {
    private val sockets = BridgeSockets(onMain = Runnable::run)
    private val received = LinkedBlockingQueue<String>()
    private var channel: BridgeChannel? = null

    @After
    fun close() = sockets.close()

    private fun register(origin: String = ORIGIN): String {
        val script = sockets.register("w1", origin) { channel ->
            this.channel = channel
            channel.onReceive { received += it }
            """{"halone":"connect","windowId":"w1"}"""
        }
        return Regex("""/([0-9a-f]{48})'""").find(script)!!.groupValues[1]
    }

    private fun open(token: String, origin: String = ORIGIN): Pair<Socket, String> {
        val socket = Socket("127.0.0.1", sockets.port)
        socket.soTimeout = 5000
        socket.getOutputStream().write(
            ("GET /$token HTTP/1.1\r\nHost: 127.0.0.1\r\nUpgrade: websocket\r\nConnection: Upgrade\r\n" +
                "Sec-WebSocket-Key: dGhlIHNhbXBsZSBub25jZQ==\r\nSec-WebSocket-Version: 13\r\nOrigin: $origin\r\n\r\n").toByteArray(),
        )
        val status = StringBuilder()
        while (true) {
            val b = socket.getInputStream().read()
            if (b < 0 || b == '\n'.code) break
            if (b != '\r'.code) status.append(b.toChar())
        }
        return socket to status.toString()
    }

    @Test
    fun `a registered page gets the connect message first, then talks both ways`() {
        val token = register()
        val (socket, status) = open(token)
        socket.use {
            assertEquals("HTTP/1.1 101 Switching Protocols", status)
            val input = DataInputStream(socket.getInputStream())
            skipHeaders(input)
            assertEquals("""{"halone":"connect","windowId":"w1"}""", readText(input))
            sendMasked(socket.getOutputStream(), """{"type":"req","id":1}""")
            assertEquals("""{"type":"req","id":1}""", received.poll(5, TimeUnit.SECONDS))
            channel!!.post("x".repeat(70_000))
            assertEquals(70_000, readText(input).length)
        }
    }

    @Test
    fun `another origin or an unknown path is refused`() {
        val token = register()
        assertEquals("HTTP/1.1 403 Forbidden", open(token, "https://evil.example").second)
        assertEquals("HTTP/1.1 403 Forbidden", open("0".repeat(48)).second)
        assertTrue(received.isEmpty())
    }

    private fun skipHeaders(input: DataInputStream) {
        var newlines = 0
        while (newlines < 2) {
            val b = input.read()
            newlines = if (b == '\n'.code) newlines + 1 else if (b == '\r'.code) newlines else 0
        }
    }

    private fun readText(input: DataInputStream): String {
        input.readUnsignedByte()
        var length = input.readUnsignedByte() and 0x7F
        if (length == 126) length = input.readUnsignedShort() else if (length == 127) length = input.readLong().toInt()
        return ByteArray(length).also(input::readFully).toString(Charsets.UTF_8)
    }

    private fun sendMasked(output: OutputStream, text: String) {
        val payload = text.toByteArray()
        val mask = byteArrayOf(1, 2, 3, 4)
        output.write(0x81)
        output.write(0x80 or payload.size)
        output.write(mask)
        output.write(ByteArray(payload.size) { (payload[it].toInt() xor mask[it % 4].toInt()).toByte() })
        output.flush()
    }

    private companion object {
        const val ORIGIN = "https://halone.local"
    }
}
